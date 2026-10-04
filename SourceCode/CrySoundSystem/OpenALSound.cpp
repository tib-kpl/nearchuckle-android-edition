#ifndef LINUX64
#include "CrySound.h"
#else
#include "CrySound64.h"
#endif

#include <AL/al.h>
#include <AL/alc.h>
#include <cstdio>
#include <cstdlib>
#include <chrono>
#include <string>
#include <vector>
#include "stb_vorbis.c"

// Timing of the slow calls of this sound layer, appended to "sound_timing.txt" in the game folder:
// each call that takes 20 ms or more, with the time since the first one, to find what delays the
// sound at start.
struct ALScopedTimer
{
	const char *op;
	std::string name;
	std::chrono::steady_clock::time_point t0;

	ALScopedTimer(const char *o, const char *n) : op(o), name(n ? n : ""), t0(std::chrono::steady_clock::now()) {}

	~ALScopedTimer()
	{
		static std::chrono::steady_clock::time_point s_first = t0;
		auto now = std::chrono::steady_clock::now();
		double ms = std::chrono::duration<double, std::milli>(now - t0).count();
		if (ms < 20.0)
			return;
		const char *dir = getenv("FARCRY_DATA_DIR");
		std::string path = std::string((dir && *dir) ? dir : ".") + "/sound_timing.txt";
		if (FILE *f = fopen(path.c_str(), "a"))
		{
			double since = std::chrono::duration<double, std::milli>(t0 - s_first).count();
			fprintf(f, "t=%8.0f ms  %-16s %8.1f ms  %.120s\n", since, op, ms, name.c_str());
			fclose(f);
		}
	}
};

#define MAX_SOUND_FILENAME 128
#define MIN_QUEUED_BUFFERS 20
// Buffers queued before playback starts, so the beginning of streamed music is not starved.
#define STREAM_PREFILL_BUFFERS 4

#ifndef __linux
#define __builtin_trap void
#endif

#if 0
#define AL_LOG(...) printf(__VA_ARGS__);
#else
#define AL_LOG
#endif

typedef struct
{
	ALuint buf;
	int flags;
	char filename[MAX_SOUND_FILENAME];
	float min_dist;
	float max_dist;
} ALSample_t;

static int audio_ogg_from_data(unsigned char* p, int bufsize, ALuint* buf);

typedef struct
{
	stb_vorbis* ogg;
	unsigned char* filebuf;
} AL_OGG_Userdata_t;

typedef struct
{
	CS_STREAMCALLBACK callback;
	char* buffer;
	int len;
	// Real sample rate of the stream. Feeding a wrong rate to alBufferData plays the
	// music at the wrong speed/pitch, so it is taken from the decoder / caller.
	int sample_rate;
#ifdef LINUX64
	void* userdata;
#else
	int userdata;
#endif
	ALuint source;
	int channel;
} ALStream_t;

ALCdevice* aldevice;
ALCcontext* alcontext;
#define MAX_SOURCES 30
ALuint sources[MAX_SOURCES];
std::vector<ALSample_t*> buffers;
std::vector<ALStream_t*> streams;
CS_OPENCALLBACK my_fopen;
CS_CLOSECALLBACK my_fclose;
CS_READCALLBACK my_fread;
CS_SEEKCALLBACK my_fseek;
CS_TELLCALLBACK my_ftell;

#define SOURCE_OUT_OF_BOUNDS 0

ALuint GetSourceOfChannel(int channel)
{
	size_t i;
	if (channel < 0)
	{
		return SOURCE_OUT_OF_BOUNDS;
	}

	if (channel >= MAX_SOURCES)
	{
		for (i = 0; i < streams.size(); i++)
		{
			if (streams[i]->channel == channel)
			{
				return streams[i]->source;
			}
		}
		return SOURCE_OUT_OF_BOUNDS;
	}
	else
	{
		return sources[channel];
	}
}

ALSample_t* GetSampleFromName(const char* filename)
{
	for (size_t i = 0; i < buffers.size(); i++)
	{
		if (!strcmp(buffers[i]->filename, filename))
		{
			return buffers[i];
		}
	}

	return NULL;
}

static unsigned int s_nNextSourceIndex = 0;

int audio_next_available_source(void)
{
	int status;
	unsigned int i;

	for (i = 0; i < MAX_SOURCES; i++)
	{
		unsigned int idx = (s_nNextSourceIndex + i) % MAX_SOURCES;
		if (sources[idx] == 0)
			continue;
		alGetSourcei(sources[idx], AL_SOURCE_STATE, &status);
		if (status != AL_PLAYING && status != AL_PAUSED)
		{
			s_nNextSourceIndex = (idx + 1) % MAX_SOURCES;
			return (int)idx;
		}
	}

	return -1;
}

DLL_API signed char     F_API CS_Init(int mixrate, int maxsoftwarechannels, unsigned int flags)
{
	ALScopedTimer timing("CS_Init", "");
	aldevice = alcOpenDevice(0);
	alcontext = alcCreateContext(aldevice, 0);
	alcMakeContextCurrent(alcontext);
	alGenSources(MAX_SOURCES, sources);
	return 1;
}

DLL_API void            F_API CS_Close()
{
	AL_LOG("OpenAL: Deleting %lu buffers.\n", buffers.size());
	size_t i;
	for (i = 0; i < buffers.size(); i++)
	{
		alDeleteBuffers(1, &buffers[i]->buf);
		delete buffers[i];
	}
	buffers.clear();
	for (i = 0; i < streams.size(); i++)
	{
		CS_Stream_Close((CS_STREAM*)streams[i]);
	}

	alDeleteSources(MAX_SOURCES, sources);
	alcMakeContextCurrent(0);
	if (alcontext) alcDestroyContext(alcontext);
	if (aldevice) alcCloseDevice(aldevice);
}

DLL_API signed char     F_API CS_SetOutput(int outputtype)
{
	return 0;
}
DLL_API signed char     F_API CS_SetDriver(int driver)
{
	return 0;
}
DLL_API signed char     F_API CS_SetMixer(int mixer)
{
	return 0;
}
DLL_API signed char     F_API CS_SetBufferSize(int len_ms)
{
	return 0;
}
DLL_API signed char     F_API CS_SetHWND(void *hwnd)
{
	return 0;
}
DLL_API signed char     F_API CS_SetMinHardwareChannels(int min)
{
	return 0;
}
DLL_API signed char     F_API CS_SetMaxHardwareChannels(int max)
{
	return 0;
}
DLL_API signed char     F_API CS_SetMemorySystem(void *pool, 
                                                     int poollen, 
                                                     CS_ALLOCCALLBACK   useralloc,
                                                     CS_REALLOCCALLBACK userrealloc,
                                                     CS_FREECALLBACK    userfree)
{
	return 0;
}

DLL_API signed char     F_API CS_SetFrequency(int channel, int freq)
{
	return 0;
}
DLL_API signed char     F_API CS_SetVolume(int channel, int vol)
{
	ALuint source = GetSourceOfChannel(channel);
	alSourcef(source, AL_GAIN, (float)vol / 255.0f);
	return 1;
}

DLL_API signed char     F_API CS_SetPan(int channel, int pan)
{
	return 0;
}

DLL_API signed char     F_API CS_SetMute(int channel, signed char mute)
{
	return 0;
}
DLL_API signed char     F_API CS_SetPriority(int channel, int priority)
{
	return 0;
}

DLL_API signed char     F_API CS_SetPaused(int channel, signed char paused)
{
	ALuint source = GetSourceOfChannel(channel);

	if (paused)
	{
		alSourcePause(source);
	}
	else
	{
		alSourcePlay(source);
	}

	return 1;
}
DLL_API signed char     F_API CS_SetLoopMode(int channel, unsigned int loopmode)
{
	return 0;
}
DLL_API signed char     F_API CS_SetCurrentPosition(int channel, unsigned int offset)
{
	return 0;
}

static void stream_read(void* dest, void** source, size_t size)
{
	memcpy(dest, *source, size);
	*source = (char*)*source + size;
}

static int audio_wav_from_data_MEM(void* p, int bufsize, ALuint* buf)
{
	ALenum ALformat;
	char header[4], wave_header[4], subchunk1[4], subchunk2[4];
	char* temp_buffer;
	unsigned int size, frequency, subchunk2size;
	unsigned short num_channels, bits_per_sample, format;
	void* start = p;
	*buf = 0;

	stream_read(&header, &p, sizeof(header));

	if (strncmp(header, "RIFF", 4))
	{
		AL_LOG("Bad RIFF header\n");
		return 1;
	}

	stream_read(&size, &p, sizeof(size));
	stream_read(&wave_header, &p, sizeof(wave_header));

	if (strncmp(wave_header, "WAVE", 4))
	{
		AL_LOG("Bad WAVE header\n");
		return 1;
	}

	stream_read(&subchunk1, &p, sizeof(subchunk1));

	if (strncmp(subchunk1, "fmt", 3))
	{
		AL_LOG("Bad subchunk - expected \"fmt\", got %s\n", subchunk1);
		return 1;
	}

	p = (char*)p + sizeof(unsigned int); /* Subchunk 1 size */
	stream_read(&format, &p, sizeof(format));

	if (format != 1)
	{
		AL_LOG("Format is %i, expected 1\n", format);
		return 1;
	}

	stream_read(&num_channels, &p, sizeof(num_channels));
	stream_read(&frequency, &p, sizeof(frequency));

	p = (char*)p + sizeof(unsigned int); /* ByteRate */
	p = (char*)p + sizeof(unsigned short); /* BlockAlign */

	stream_read(&bits_per_sample, &p, sizeof(bits_per_sample));

	switch (bits_per_sample)
	{
		case 8:
			ALformat = num_channels == 2 ? AL_FORMAT_STEREO8 : AL_FORMAT_MONO8;
		break;
		case 16:
			ALformat = num_channels == 2 ? AL_FORMAT_STEREO16 : AL_FORMAT_MONO16;
		break;
		default:
			AL_LOG("bits_per_sample is %i, expected 8 for 16\n", bits_per_sample);
			return 1;
		break;
	}

	memset(subchunk2, 0, 4);
	p = start;
	while (strncmp(subchunk2, "data", 4))
	{
		if (((char*)p + 4 - (char*)start) >= bufsize)
		{
			//end of file, give up
			break;
		}
		stream_read(&subchunk2, &p, sizeof(subchunk2));
		if (strncmp(subchunk2, "data", 4))
		{
			p = (char*)p - 3;
		}
		else
		{
			break;
		}
	}

	if (strncmp(subchunk2, "data", 4))
	{
		AL_LOG("Subchunk 2 ID is %s, expected \"data\"\n", subchunk2);
		return 1;
	}

	stream_read(&subchunk2size, &p, sizeof(subchunk2size));

	temp_buffer = new char[subchunk2size];

	stream_read(temp_buffer, &p, subchunk2size);

	alGenBuffers(1, buf);
	alBufferData(*buf, ALformat, temp_buffer, subchunk2size, frequency);
	delete [] temp_buffer;
	return 0;
}

#ifndef LINUX64
DLL_API CS_SAMPLE* F_API CS_Sample_Load(int index, const char* name_or_data, unsigned int mode, int memlength)
#else
DLL_API CS_SAMPLE * F_API CS_Sample_Load(int index, const char *name_or_data, unsigned int mode, int offset, int length)
#endif
{
	ALScopedTimer timing("CS_Sample_Load", name_or_data);
#ifndef LINUX64
	int length = memlength;
#endif
	ALuint thebuf = 0;
	int ret = -1;
	ALSample_t* samp = nullptr;
	if (mode & CS_LOADMEMORY)
	{
		ret = audio_wav_from_data_MEM((void*)name_or_data, length, &thebuf);
		if (ret != 0)
		{
			ret = audio_ogg_from_data((unsigned char*)name_or_data, length, &thebuf);
		}
		if (ret == 0)
		{
			samp = new ALSample_t;
			samp->buf = thebuf;
			samp->flags = mode;
			samp->min_dist = 1.0f;
			samp->max_dist = 1000.0f;
			strcpy(samp->filename, "<MEMORY>");

			buffers.push_back(samp);
			AL_LOG("OpenAL: There are now %i buffers.\n", buffers.size());
		}
		else
		{
			AL_LOG("OpenAL: Failed to load sound from memory\n");
		}
	}
	return (CS_SAMPLE*)samp;
}

DLL_API CS_SAMPLE* F_API CS_Sample_Alloc(int index, int length, unsigned int mode, int deffreq, int defvol, int defpan, int defpri)
{
	return 0;
}

DLL_API void            F_API CS_Sample_Free(CS_SAMPLE* sptr)
{
	std::vector<ALSample_t*>::iterator it;
	ALSample_t* samp = (ALSample_t*)sptr;

	for (it = buffers.begin(); it != buffers.end(); it++)
	{
		if (*it == samp)
		{
			alDeleteBuffers(1, &(*it)->buf);
			buffers.erase(it);
			return;
		}
	}
}
#if 0
DLL_API signed char     F_API CS_Sample_Upload(CS_SAMPLE* sptr, void* srcdata, unsigned int mode)
{
	return 0;
}

DLL_API signed char     F_API CS_Sample_Lock(CS_SAMPLE* sptr, int offset, int length, void** ptr1, void** ptr2, unsigned int* len1, unsigned int* len2)
{
	return 0;
}

DLL_API signed char     F_API CS_Sample_Unlock(CS_SAMPLE* sptr, void* ptr1, void* ptr2, unsigned int len1, unsigned int len2)
{
	return 0;
}

DLL_API int             F_API CS_GetError()
{
	return 0;
}
#endif
DLL_API float           F_API CS_GetVersion()
{
	return CS_VERSION;
}

DLL_API int             F_API CS_GetOutput()
{
	return 0;
}
#if 0
DLL_API void* F_API CS_GetOutputHandle()
{
	return 0;
}
#endif
DLL_API int             F_API CS_GetDriver()
{
	return 0;
}

DLL_API int             F_API CS_GetMixer()
{
	return 0;
}

DLL_API int             F_API CS_GetNumDrivers()
{
	return 1;
}

#ifndef LINUX64
DLL_API signed char* F_API CS_GetDriverName(int id)
#else
DLL_API const char *    F_API CS_GetDriverName(int id)
#endif
{
	if (id == 0)
	{
#ifndef LINUX64
		return (signed char*)"OpenAL";
#else
		return "OpenAL";
#endif
		
	}
	return nullptr;
}

DLL_API signed char     F_API CS_GetDriverCaps(int id, unsigned int* caps)
{
	return 0;
}

DLL_API int             F_API CS_GetOutputRate()
{
	return 0;
}

DLL_API int             F_API CS_GetMaxChannels()
{
	return 0;
}

DLL_API int             F_API CS_GetMaxSamples()
{
	return 0;
}

DLL_API int             F_API CS_GetSFXMasterVolume()
{
	return 0;
}

DLL_API int             F_API CS_GetNumHardwareChannels()
{
	return 0;
}

DLL_API int             F_API CS_GetChannelsPlaying()
{
	return 0;
}

DLL_API float           F_API CS_GetCPUUsage()
{
	return 0;
}

DLL_API void            F_API CS_GetMemoryStats(unsigned int* currentalloced, unsigned int* maxalloced)
{
	
}

DLL_API signed char     F_API CS_Stream_SetBufferSize(int ms)
{
	return 0;
}

#ifndef LINUX64
DLL_API CS_STREAM* F_API CS_Stream_Open(const char* name_or_data, unsigned int mode, int offset, int length);
#endif

DLL_API CS_STREAM* F_API CS_Stream_OpenFile(const char* filename, unsigned int mode, int memlength)
{
	return CS_Stream_Open(filename, mode, 0, memlength);
}

static int audio_ogg_from_data(unsigned char* p, int bufsize, ALuint* buf)
{
	ALshort* ogg_buffer;
	int size, length_samples, vorbis_error;
	stb_vorbis_info info;
	ALenum format;
	stb_vorbis* ogg = stb_vorbis_open_memory(p, bufsize, &vorbis_error, NULL);

	if (!ogg)
	{
		return vorbis_error;
	}

	info = stb_vorbis_get_info(ogg);
	format = (info.channels == 1) ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
	length_samples = stb_vorbis_stream_length_in_samples(ogg) * info.channels;
	size = length_samples * sizeof(ALshort);

	ogg_buffer = (ALshort*)malloc(size);

	stb_vorbis_get_samples_short_interleaved(ogg,
		info.channels, ogg_buffer, length_samples);

	stb_vorbis_close(ogg);

	alGenBuffers(1, buf);
	alBufferData(*buf, format, ogg_buffer, size, info.sample_rate);

	free(ogg_buffer);

	return 0;
}

#ifdef LINUX64
signed char StreamOGGCallback(CS_STREAM* pStream, void *pBuffer, int nLength, void* nParam)
#else
signed char StreamOGGCallback(CS_STREAM* pStream, void* pBuffer, int nLength, int nParam)
#endif
{
	AL_OGG_Userdata_t* userdata = (AL_OGG_Userdata_t*)nParam;
	int read_samples = stb_vorbis_get_samples_short_interleaved(userdata->ogg,
		userdata->ogg->channels, (short*)pBuffer, nLength / sizeof(short));
	return 0;
}

DLL_API CS_STREAM*    F_API CS_Stream_Open(const char *name_or_data, unsigned int mode, int offset, int length)
{
	ALScopedTimer timing("CS_Stream_Open", name_or_data);
	if (!name_or_data)
		return NULL;

#ifndef LINUX64
	unsigned int file = my_fopen(name_or_data);
#else
	FILE *file = (FILE*)my_fopen(name_or_data);
#endif
	int len, ret, vorbis_error;
	unsigned char* buf;
	ALuint thebuf = 0;
	stb_vorbis* ogg;
	stb_vorbis_info info;
	
	ALStream_t* stream = nullptr;
	AL_OGG_Userdata_t* userdata = nullptr;
	const char* ext = strrchr(name_or_data, '.');

	if (!ext)
	{
		if (file) my_fclose(file);
		return NULL;
	}

	if (!strcmp(ext, ".ogg"))
	{
		if (file)
		{
			my_fseek(file, 0, SEEK_END);
			len = my_ftell(file);
			
			buf = new unsigned char [len];
			my_fseek(file, 0, SEEK_SET);
			my_fread(buf, len, file);
			my_fclose(file);

			ogg = stb_vorbis_open_memory(buf, len, &vorbis_error, NULL);

			if (!ogg)
			{
				delete [] buf;
				return NULL;
			}

			info = stb_vorbis_get_info(ogg);
			stream = new ALStream_t;

			userdata = new AL_OGG_Userdata_t;
			userdata->ogg = ogg;
			userdata->filebuf = buf;

			alGenSources(1, &stream->source);
			stream->buffer = new char[4096];
			stream->len = 4096;
			stream->sample_rate = (info.sample_rate > 0) ? info.sample_rate : 44100;
			stream->callback = StreamOGGCallback;
#ifdef LINUX64
			stream->userdata = userdata;
#else
			stream->userdata = (int)userdata;
#endif
			stream->channel = CS_FREE;
			streams.push_back(stream);

			AL_LOG("OpenAL %s: There are now %lu streams.\n", __func__, streams.size());
		}
		else
		{
			return NULL;
		}

	}
	else if (!strcmp(ext, ".wav"))
	{
		if (file) my_fclose(file);
		AL_LOG("Error, WAV stream not handled\n");
		return NULL;
	}
	else
	{
		if (file) my_fclose(file);
		return NULL;
	}

	return (CS_STREAM*)stream;
}

#ifndef LINUX64
DLL_API CS_STREAM* F_API CS_Stream_Create(CS_STREAMCALLBACK callback, int length, unsigned int mode, int samplerate, int userdata)
#else
DLL_API CS_STREAM* F_API CS_Stream_Create(CS_STREAMCALLBACK callback, int length, unsigned int mode, int samplerate, void *userdata)
#endif
{
	ALStream_t* stream = new ALStream_t;
	alGenSources(1, &stream->source);
	stream->buffer = new char[length];
	stream->len = length;
	stream->sample_rate = (samplerate > 0) ? samplerate : 44100;
	stream->callback = callback;
	stream->userdata = userdata;
	stream->channel = CS_FREE;

	memset(stream->buffer, 0, length);

	streams.push_back(stream);
	AL_LOG("OpenAL %s: There are now %lu streams.\n", __func__, streams.size());

	return (CS_STREAM*)stream;
}

DLL_API signed char     F_API CS_Stream_Close(CS_STREAM* stream)
{
	ALStream_t* strm = (ALStream_t*)stream;
	AL_OGG_Userdata_t* ogg_userdata;
	std::vector<ALStream_t*>::iterator it;

	if (!stream)
	{
		return 0;
	}

	CS_Stream_Stop(stream);

	for (it = streams.begin(); it != streams.end(); it++)
	{
		if (*it == strm)
		{
			streams.erase(it);
			AL_LOG("OpenAL %s: There are now %lu streams.\n", __func__, streams.size());
			break;
		}
	}

	if (strm->callback == &StreamOGGCallback)
	{
		ogg_userdata = (AL_OGG_Userdata_t*)strm->userdata;
		stb_vorbis_close(ogg_userdata->ogg);
		delete [] ogg_userdata->filebuf;
	}

	alDeleteSources(1, &strm->source);
	delete [] strm->buffer;
	delete strm;

	return 1;
}

DLL_API int             F_API CS_Stream_Play(int channel, CS_STREAM* stream)
{
	ALStream_t* strm = (ALStream_t*)stream;
	int i;
	if (!strm)
		return -1;

	alSourcei(strm->source, AL_SOURCE_RELATIVE, AL_TRUE);
	alSource3f(strm->source, AL_POSITION, 0.0f, 0.0f, 0.0f);
	alSource3f(strm->source, AL_VELOCITY, 0.0f, 0.0f, 0.0f);
	alSourcePlay(strm->source);

	for (i = 0; i < (int)streams.size(); i++)
	{
		if (strm == streams[i])
		{
			break;
		}
	}

	strm->channel = MAX_SOURCES + i;
	return MAX_SOURCES + i;
}

DLL_API int             F_API CS_Stream_PlayEx(int channel, CS_STREAM* stream, CS_DSPUNIT* dsp, signed char startpaused)
{
	ALStream_t* strm = (ALStream_t*)stream;
	int i;
	ALuint stream_buf;
	if (!strm)
		return -1;

	alSourcei(strm->source, AL_SOURCE_RELATIVE, AL_TRUE);
	alSource3f(strm->source, AL_POSITION, 0.0f, 0.0f, 0.0f);
	alSource3f(strm->source, AL_VELOCITY, 0.0f, 0.0f, 0.0f);

	if (strm->callback)
	{
		// Queue a few buffers before starting instead of a single one: with only one small
		// buffer queued the source starves on the first frames and the music crackles.
		// Bink streams keep the old single-buffer path - their payload length is fixed up
		// later in UpdateStream().
		int prefill = (strm->len == 138240) ? 1 : STREAM_PREFILL_BUFFERS;
		int n;
		for (n = 0; n < prefill; n++)
		{
			strm->callback((CS_STREAM*)stream, strm->buffer,
						strm->len, strm->userdata);

			alGenBuffers(1, &stream_buf);
			alBufferData(stream_buf, AL_FORMAT_STEREO16,
				(ALvoid *)strm->buffer, strm->len, strm->sample_rate);
			alSourceQueueBuffers(strm->source, 1, &stream_buf);
		}
	}

	alSourcePlay(strm->source);
	if (startpaused)
	{
		alSourcePause(strm->source);
	}

	for (i = 0; i < (int)streams.size(); i++)
	{
		if (strm == streams[i])
		{
			break;
		}
	}

	strm->channel = MAX_SOURCES + i;
	return MAX_SOURCES + i;
}

DLL_API signed char     F_API CS_Stream_Stop(CS_STREAM* stream)
{
	ALStream_t* strm;
	int i, num_buffers;
	ALuint buffer;

	if (!stream)
	{
		return 0;
	}

	strm = (ALStream_t*)stream;
	strm->channel = CS_FREE;
	alSourceStop(strm->source);
	alGetSourcei(strm->source, AL_BUFFERS_QUEUED, &num_buffers);

	for (i = 0; i < num_buffers; i++)
	{
		alSourceUnqueueBuffers(strm->source, 1, &buffer);
		alDeleteBuffers(1, &buffer);
	}

	return 1;
}
#if 0
DLL_API int             F_API CS_Stream_GetOpenState(CS_STREAM* stream)
{
	return 0;
}
#endif
DLL_API signed char     F_API CS_Stream_SetPosition(CS_STREAM* stream, unsigned int position)
{
	return 0;
}

DLL_API unsigned int    F_API CS_Stream_GetPosition(CS_STREAM* stream)
{
	return 0;
}

DLL_API signed char     F_API CS_Stream_SetTime(CS_STREAM* stream, int ms)
{
	return 0;
}

DLL_API int             F_API CS_Stream_GetTime(CS_STREAM* stream)
{
	return 0;
}

DLL_API int             F_API CS_Stream_GetLength(CS_STREAM* stream)
{
	return 0;
}

DLL_API int             F_API CS_Stream_GetLengthMs(CS_STREAM* stream)
{
	unsigned int lengthInSamples = CS_Stream_GetLength(stream);
	ALSample_t* samp = (ALSample_t*)stream;
	ALint frequency;
	float durationInMilliseconds;
	alGetBufferi(samp->buf, AL_FREQUENCY, &frequency);
	durationInMilliseconds = ((float)lengthInSamples / (float)frequency) * 1000.0f;
	return (int)durationInMilliseconds;
}

DLL_API int             F_API CS_FX_Enable(int channel, unsigned int fx)
{
	return 0;
}

DLL_API signed char     F_API CS_FX_SetI3DL2Reverb(int fxid, int Room, int RoomHF,
	float RoomRolloffFactor, float DecayTime, float DecayHFRatio, int Reflections,
	float ReflectionsDelay, int Reverb, float ReverbDelay, float Diffusion,
	float Density, float HFReference)
{
	return 0;
}

DLL_API signed char     F_API CS_FX_SetParamEQ(int fxid, float Center, float Bandwidth,
	float Gain)
{
	return 0;
}
DLL_API signed char     F_API CS_FX_SetWavesReverb(int fxid, float InGain, float ReverbMix,
	float ReverbTime, float HighFreqRTRatio)
{
	return 0;
}

//A hack for Bink audio streams from libbinkdec, which
//buffer a large amount of bytes at the start, but then
//send much less afterward. Check how many bytes were
//actually processed, then send only that much to OpenAL.
static int BytesFromBinkDec(char* buffer, int len)
{
	int i, j, bytes_processed;
	const int MAX_END_CHECK = 100;
	bool hit_end = false;

	for (i = bytes_processed = 0; i < len - MAX_END_CHECK; i++)
	{
		if (buffer[i] != -1)
		{
			bytes_processed++;
		}
		else
		{
			hit_end = true;
			for (j = 1; j < MAX_END_CHECK; j++)
			{
				if (buffer[i + j] != -1)
				{
					hit_end = false;
					break;
				}
			}
			
			if (hit_end)
			{
				break;
			}
			else
			{
				bytes_processed++;
			}
		}
	}

	if (bytes_processed == len - MAX_END_CHECK)
	{
		bytes_processed += MAX_END_CHECK;
	}

	return bytes_processed;
}

static void UpdateStream(ALStream_t* stream)
{
	if (!stream || stream->channel == CS_FREE)
	{
		return;
	}

	ALenum state;
	alGetSourcei(stream->source, AL_SOURCE_STATE, &state);
	if (state == AL_PAUSED)
	{
		return;
	}

	ALuint buffer, stream_buf;
	int i, bytes_processed;
	int num_processed_buffers = 0;
	int num_queued_buffers = 0;

	alGetSourcei(stream->source, AL_BUFFERS_PROCESSED, &num_processed_buffers);

	for (i = 0; i < num_processed_buffers; i++)
	{
		alSourceUnqueueBuffers(stream->source, 1, &buffer);
		alDeleteBuffers(1, &buffer);
	}

	alGetSourcei(stream->source, AL_BUFFERS_QUEUED, &num_queued_buffers);

	if (num_queued_buffers < MIN_QUEUED_BUFFERS && stream->callback)
	{
		// Refill everything that is missing in this update instead of a single buffer.
		// Queueing only one buffer per CS_Update() starves the source whenever a frame
		// takes longer than one buffer (the main menu renders a 3D background and drops
		// frames), which is heard as crackling/broken music. The loop is bounded by the
		// number of missing buffers, so it can never spin.
		int missing = MIN_QUEUED_BUFFERS - num_queued_buffers;

		for (i = 0; i < missing && num_queued_buffers < MIN_QUEUED_BUFFERS; i++)
		{
			stream->callback((CS_STREAM*)stream, stream->buffer,
				stream->len, stream->userdata);

			if (stream->len == 138240)
			{
				bytes_processed = BytesFromBinkDec(stream->buffer, stream->len);
			}
			else
			{
				bytes_processed = stream->len;
			}

			if (bytes_processed <= 0)
			{
				break;
			}

			alGenBuffers(1, &stream_buf);
			alBufferData(stream_buf, AL_FORMAT_STEREO16,
				(ALvoid *)stream->buffer, bytes_processed, stream->sample_rate);
			alSourceQueueBuffers(stream->source, 1, &stream_buf);
			alGetSourcei(stream->source, AL_BUFFERS_QUEUED, &num_queued_buffers);

			if (state != AL_PLAYING && state != AL_PAUSED && stream->channel != CS_FREE)
			{
				alSourcePlay(stream->source);
			}
		}
	}
}

DLL_API void            F_API CS_Update()
{
	size_t i;
	for (i = 0; i < streams.size(); i++)
	{
		UpdateStream(streams[i]);
	}
}

DLL_API void            F_API CS_SetSpeakerMode(unsigned int speakermode)
{

}
DLL_API void            F_API CS_SetSFXMasterVolume(int volume)
{
	size_t i;
	for (i = 0; i < MAX_SOURCES; i++)
	{
		alSourcef(sources[i], AL_GAIN, (float)volume / 255.0f);
	}
}
DLL_API void            F_API CS_SetPanSeperation(float pansep)
{

}
DLL_API void            F_API CS_File_SetCallbacks(CS_OPENCALLBACK  useropen,
                                                       CS_CLOSECALLBACK userclose,
                                                       CS_READCALLBACK  userread,
                                                       CS_SEEKCALLBACK  userseek,
                                                       CS_TELLCALLBACK  usertell)
{
	my_fopen = useropen;
	my_fclose = userclose;
	my_fread = userread;
	my_fseek = userseek;
	my_ftell = usertell;
}
#if 0
DLL_API void            F_API CS_3D_SetDopplerFactor(float scale)
{

}

DLL_API void            F_API CS_3D_SetDistanceFactor(float scale)
{

}
#endif
DLL_API void            F_API CS_3D_SetRolloffFactor(float scale)
{

}

#ifndef LINUX64
DLL_API signed char     F_API CS_3D_SetAttributes(int channel, float *pos, float *vel)
#else
DLL_API signed char     F_API CS_3D_SetAttributes(int channel, const float *pos, const float *vel)
#endif
{
	if (channel < 0 || channel >= MAX_SOURCES)
	{
		return 0;
	}

	alSourcei(sources[channel], AL_SOURCE_RELATIVE, AL_FALSE);

	if (pos)
	{
		alSource3f(sources[channel], AL_POSITION, pos[0], pos[1], pos[2]);
	}
	
	if (vel)
	{
		alSource3f(sources[channel], AL_VELOCITY, vel[0], vel[1], vel[2]);
	}
	
	return 1;
}
#if 0
DLL_API signed char     F_API CS_3D_GetAttributes(int channel, float *pos, float *vel)
{
	return 0;
}

DLL_API void            F_API CS_3D_Listener_SetCurrent(int current, int numlisteners)
{

}
#endif
#ifndef LINUX64
DLL_API void            F_API CS_3D_Listener_SetAttributes(float *pos, float *vel, float fx,
	float fy, float fz, float tx, float ty, float tz)
#else
DLL_API void            F_API CS_3D_Listener_SetAttributes(const float *pos, const float *vel,
	float fx, float fy, float fz, float tx, float ty, float tz)
#endif
{
	ALfloat ori[6];
	if (pos)
	{
		alListener3f(AL_POSITION, pos[0], pos[1], pos[2]);
	}
	if (vel)
	{
		alListener3f(AL_VELOCITY, vel[0], vel[1], vel[2]);
	}

	ori[0] = -fx;
	ori[1] = fy;
	ori[2] = -fz;
	ori[3] = tx;
	ori[4] = ty;
	ori[5] = tz;
	alListenerfv(AL_ORIENTATION, ori);
}
#if 0
DLL_API void            F_API CS_3D_Listener_GetAttributes(float *pos, float *vel, float *fx,
	float *fy, float *fz, float *tx, float *ty, float *tz)
{

}
#endif
DLL_API signed char     F_API CS_IsPlaying(int channel)
{
	int status;
	ALuint source = GetSourceOfChannel(channel);
	alGetSourcei(source, AL_SOURCE_STATE, &status);
	if (status == AL_PLAYING)
	{
		return 1;
	}
	return 0;
}
#if 0
DLL_API signed char     F_API CS_GetReserved(int channel)
{
	return 0;
}
#endif
DLL_API unsigned int    F_API CS_GetLoopMode(int channel)
{
	return 0;
}
DLL_API unsigned int    F_API CS_GetCurrentPosition(int channel)
{
	int currbytes, size;
	if (channel < 0 || channel >= MAX_SOURCES)
	{
		//__builtin_trap();
		return SOURCE_OUT_OF_BOUNDS;
	}

	alGetSourcei(sources[channel], AL_BYTE_OFFSET, &currbytes);

	return currbytes;
}
DLL_API CS_SAMPLE * F_API CS_GetCurrentSample(int channel)
{
	return nullptr;
}
DLL_API signed char     F_API CS_GetCurrentLevels(int channel, float *l, float *r)
{
	return 0;
}

DLL_API signed char     F_API CS_DSP_MixBuffers(void *destbuffer, void *srcbuffer, int len, int freq, int vol, int pan, unsigned int mode)
{
	return 0;
}

DLL_API void            F_API CS_DSP_ClearMixBuffer()
{

}

DLL_API int             F_API CS_DSP_GetBufferLength()
{
	return 0;
}

DLL_API int             F_API CS_DSP_GetBufferLengthTotal()
{
	return 0;
}

DLL_API float *         F_API CS_DSP_GetSpectrum()
{
	return nullptr;
}

DLL_API CS_DSPUNIT *F_API CS_DSP_Create(CS_DSPCALLBACK callback, int priority, void *userdata)
{
	return nullptr;
}

DLL_API void            F_API CS_DSP_Free(CS_DSPUNIT *unit)
{
}

DLL_API void            F_API CS_DSP_SetActive(CS_DSPUNIT *unit, signed char active)
{
}

DLL_API unsigned int    F_API CS_Sample_GetLength(CS_SAMPLE *sptr)
{
	ALSample_t* samp = (ALSample_t*)sptr;
	ALint sizeInBytes;
	ALint channels;
	ALint bits;
	ALint frequency;
	int lengthInSamples;

	alGetBufferi(samp->buf, AL_SIZE, &sizeInBytes);
	alGetBufferi(samp->buf, AL_CHANNELS, &channels);
	alGetBufferi(samp->buf, AL_BITS, &bits);
	alGetBufferi(samp->buf, AL_FREQUENCY, &frequency);
	lengthInSamples = sizeInBytes * 8 / (channels * bits);

	return lengthInSamples;
}

DLL_API signed char     F_API CS_Sample_GetLoopPoints(CS_SAMPLE *sptr, int *loopstart, int *loopend)
{
	return 0;
}

DLL_API signed char     F_API CS_Sample_GetDefaults(CS_SAMPLE *sptr, int *deffreq, int *defvol, int *defpan, int *defpri)
{
	ALSample_t* samp = (ALSample_t*)sptr;
	if (deffreq != nullptr)
	{
		alGetBufferi(samp->buf, AL_FREQUENCY, deffreq);
	}

	return 0;
}

DLL_API signed char     F_API CS_Sample_GetDefaultsEx(CS_SAMPLE *sptr, int *deffreq, int *defvol, int *defpan, int *defpri, int *varfreq, int *varvol, int *varpan)
{
	return 0;
}

DLL_API int             F_API CS_PlaySound(int channel, CS_SAMPLE *sptr)
{
	return CS_PlaySoundEx(channel, sptr, NULL, 0);
}

DLL_API int             F_API CS_PlaySoundEx(int channel, CS_SAMPLE *sptr, CS_DSPUNIT *dsp, signed char startpaused)
{
	ALSample_t* samp = (ALSample_t*)sptr;
	int i;
	ALuint src;

	if (!samp || !samp->buf)
	{
		return -1;
	}

	if (channel == CS_FREE)
	{
		i = audio_next_available_source();
	}
	else if (channel >= 0 && channel < MAX_SOURCES)
	{
		i = channel;
	}
	else
	{
		return -1;
	}

	if (i < 0 || i >= MAX_SOURCES)
	{
		return -1;
	}

	src = sources[i];
	if (!src)
	{
		return -1;
	}

	alSourceStop(src);
	alSourcei(src, AL_BUFFER, samp->buf);
	alSourcei(src, AL_SOURCE_RELATIVE, (samp->flags & CS_HW3D) ? AL_FALSE : AL_TRUE);
	alSource3f(src, AL_POSITION, 0.0f, 0.0f, 0.0f);
	alSource3f(src, AL_VELOCITY, 0.0f, 0.0f, 0.0f);
	alSourcei(src, AL_LOOPING, (samp->flags & CS_LOOP_NORMAL) ? AL_TRUE : AL_FALSE);

	if (samp->min_dist > 0.0f)
		alSourcef(src, AL_REFERENCE_DISTANCE, samp->min_dist);
	else
		alSourcef(src, AL_REFERENCE_DISTANCE, 1.0f);

	if (samp->max_dist > 0.0f)
		alSourcef(src, AL_MAX_DISTANCE, samp->max_dist);
	else
		alSourcef(src, AL_MAX_DISTANCE, 1000.0f);

	if (startpaused)
	{
		alSourcePause(src);
	}
	else
	{
		alSourcePlay(src);
	}

	return i;
}

DLL_API signed char     F_API CS_StopSound(int channel)
{
	size_t i;
	if (channel < 0 && channel != CS_FREE)
		return 0;

	if (channel == CS_FREE)
	{
		for (i = 0; i < MAX_SOURCES; i++)
		{
			alSourceStop(sources[i]);
			alSourcei(sources[i], AL_BUFFER, 0);
		}
		for (i = 0; i < streams.size(); i++)
		{
			CS_Stream_Stop((CS_STREAM*)streams[i]);
		}
		return 1;
	}

	if (channel >= MAX_SOURCES)
	{
		for (i = 0; i < streams.size(); i++)
		{
			if (streams[i]->channel == channel)
			{
				CS_Stream_Stop((CS_STREAM*)streams[i]);
				return 1;
			}
		}
		return SOURCE_OUT_OF_BOUNDS;
	}

	alSourceStop(sources[channel]);
	alSourcei(sources[channel], AL_BUFFER, 0);
	return 1;
}

DLL_API signed char     F_API CS_Sample_SetMode(CS_SAMPLE *sptr, unsigned int mode)
{
	ALSample_t* samp = (ALSample_t*)sptr;
	if (samp)
		samp->flags = mode;
	return 1;
}

DLL_API signed char     F_API CS_Sample_SetLoopPoints(CS_SAMPLE *sptr, int loopstart, int loopend)
{
	return 0;
}

DLL_API signed char     F_API CS_Sample_SetDefaults(CS_SAMPLE *sptr, int deffreq, int defvol, int defpan, int defpri)
{
	return 0;
}

DLL_API signed char     F_API CS_Sample_SetMinMaxDistance(CS_SAMPLE *sptr, float min, float max)
{
	ALSample_t* samp = (ALSample_t*)sptr;
	if (samp)
	{
		samp->min_dist = min;
		samp->max_dist = max;
		return 1;
	}
	return 0;
}

DLL_API signed char     F_API CS_Sample_SetMaxPlaybacks(CS_SAMPLE *sptr, int max)
{
	return 0;
}

#ifndef LINUX64
DLL_API signed char   F_API   CS_Reverb_SetProperties(CS_REVERB_PROPERTIES *prop)
#else
DLL_API signed char   F_API   CS_Reverb_SetProperties(const CS_REVERB_PROPERTIES *prop)
#endif
{
	return 0;
}

DLL_API signed char     F_API CS_Reverb_GetProperties(CS_REVERB_PROPERTIES *prop)
{
	return 0;
}

DLL_API signed char     F_API CS_Reverb_SetChannelProperties(int channel, const CS_REVERB_CHANNELPROPERTIES *prop)
{
	return 0;
}

DLL_API signed char     F_API CS_Reverb_GetChannelProperties(int channel, CS_REVERB_CHANNELPROPERTIES *prop)
{
	return 0;
}