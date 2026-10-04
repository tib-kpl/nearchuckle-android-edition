#include <list>
#include <deque>
#include <algorithm>
#include <SDL3/SDL_mutex.h>
#include "StdAfx.h"
#include "ISound.h"
#include <ICryPak.h>
#include "UIVideoBinkDec.h"
#include <BinkDecoder.h>

enum EPlayerCmd : int
{
	PLAYER_CMD_NONE = 0,
	PLAYER_CMD_PLAYING,
	PLAYER_CMD_REWIND,
	PLAYER_CMD_STOP,
};

struct MoviePlayerData
{
	BinkHandle binkHandle;
	YUVbuffer yuvBuffer;
	bool hasFrame;
	int	framePos;
	int	lastFramePos;
	int	numFrames;
	uint32_t numAudioTracks;
	uint32_t trackIndex;
	AudioInfo binkInfo;
	bool looping;
	int startTime;
	float frameRate;
	unsigned int vidWidth;
	unsigned int vidHeight;
	// The sound decoded with the frames, as 16-bit stereo, waiting for the sound system (which
	// takes it in fixed blocks through BinkDecAudioCallback).
	std::deque<int16_t> pcm;
	SDL_Mutex* pcmMutex = nullptr;
	uint8_t* audioScratch = nullptr;
};

// block of sound handed to the sound system at a time: 1024 stereo frames
static const int kBinkAudioBlockBytes = 1024 * 2 * sizeof(int16_t);
// the sound system's "pull" stream (OpenALSound.cpp): only the blocks the callback has are queued
#define CS_STREAM_PULL 0x40000000

static MoviePlayerData* CreatePlayerData(const char* filename)
{
	MoviePlayerData* player = new MoviePlayerData();
	uint32_t w = 0, h = 0;
	ILog* iLog = GetISystem()->GetILog();
	player->looping = 0;

	char normalized[1024];
	strncpy(normalized, filename, sizeof(normalized) - 1);
	normalized[sizeof(normalized) - 1] = 0;
	for (char* p = normalized; *p; ++p)
	{
		if (*p == '\\') *p = '/';
	}
	char* corrected = (char*)alloca(strlen(normalized) + 3);
	if (casepath(normalized, corrected))
	{
		player->binkHandle = Bink_Open( corrected );
	}
	if (!player->binkHandle.isValid && GetISystem() && GetISystem()->GetIPak())
	{
		char adjusted[1024];
		const char* pAdj = GetISystem()->GetIPak()->AdjustFileName(normalized, adjusted, ICryPak::FLAGS_PATH_REAL);
		if (pAdj && pAdj[0])
		{
			char* adjCorrected = (char*)alloca(strlen(pAdj) + 3);
			if (casepath(pAdj, adjCorrected))
				player->binkHandle = Bink_Open(adjCorrected);
			if (!player->binkHandle.isValid)
				player->binkHandle = Bink_Open(pAdj);
		}
	}
	if (!player->binkHandle.isValid)
	{
		player->binkHandle = Bink_Open( normalized );
	}
	if (!player->binkHandle.isValid)
	{
		player->binkHandle = Bink_Open( filename );
	}
	if (!player->binkHandle.isValid)
	{
		if (iLog) iLog->LogError("Failed to open video file %s", filename);
		delete player;
		return nullptr;
	}

	Bink_GetFrameSize( player->binkHandle, w, h );
	player->vidWidth = w;
	player->vidHeight = h;

	player->frameRate = Bink_GetFrameRate(player->binkHandle);
	player->numFrames = Bink_GetNumFrames(player->binkHandle);
	float durationSec = player->numFrames / player->frameRate;
	int animationLength = durationSec * 1000;

	player->framePos = -1;
	player-> lastFramePos = -1; 

	return player;
}

#if (defined(__GNUC__) || defined(__clang__)) && !defined(_WIN32)
extern "C" {
#ifndef LINUX64
__attribute__((weak)) CS_STREAM* CS_Stream_Create(CS_STREAMCALLBACK callback, int length, unsigned int mode, int samplerate, int userdata)
#else
__attribute__((weak)) CS_STREAM* CS_Stream_Create(CS_STREAMCALLBACK callback, int length, unsigned int mode, int samplerate, void* userdata)
#endif
{
	return nullptr;
}
__attribute__((weak)) signed char CS_Stream_Close(CS_STREAM* stream)
{
	return 0;
}
__attribute__((weak)) int CS_Stream_Play(int channel, CS_STREAM* stream)
{
	return 0;
}
__attribute__((weak)) signed char CS_Stream_Stop(CS_STREAM* stream)
{
	return 0;
}
__attribute__((weak)) void CS_Update()
{
}
}
#endif

CUIVideoBinkDecoder::~CUIVideoBinkDecoder()
{
	Terminate();
}

CUIVideoBinkDecoder::CUIVideoBinkDecoder(const char* aliasName)
{
	m_aliasName = aliasName;
}

signed char BinkDecAudioCallback(CS_STREAM* pStream, void* pBuffer, int nLength, void* nParam)
{
	MoviePlayerData* player = (MoviePlayerData*)nParam;
	if (!player || !player->pcmMutex)
		return 0;
	SDL_LockMutex(player->pcmMutex);
	const size_t samples = nLength / sizeof(int16_t);
	bool ok = player->pcm.size() >= samples;
	if (ok)
	{
		int16_t* out = (int16_t*)pBuffer;
		std::copy(player->pcm.begin(), player->pcm.begin() + samples, out);
		player->pcm.erase(player->pcm.begin(), player->pcm.begin() + samples);
	}
	SDL_UnlockMutex(player->pcmMutex);
	return ok ? 1 : 0;
}

/** The sound of the frame just decoded, added to the queue (as stereo). */
static void BinkQueueFrameAudio(MoviePlayerData* player)
{
	if (!player->pcmMutex || !player->audioScratch)
		return;
	uint32_t bytes = Bink_GetAudioData(player->binkHandle, player->trackIndex, (int16_t*)player->audioScratch);
	if (bytes > player->binkInfo.idealBufferSize)
		bytes = player->binkInfo.idealBufferSize;
	const int16_t* in = (const int16_t*)player->audioScratch;
	const uint32_t n = bytes / sizeof(int16_t);
	SDL_LockMutex(player->pcmMutex);
	if (player->binkInfo.nChannels == 1)
	{
		for (uint32_t i = 0; i < n; i++)
		{
			player->pcm.push_back(in[i]);
			player->pcm.push_back(in[i]);
		}
	}
	else
	{
		player->pcm.insert(player->pcm.end(), in, in + n);
	}
	SDL_UnlockMutex(player->pcmMutex);
}

static void BinkClearAudio(MoviePlayerData* player)
{
	if (!player || !player->pcmMutex)
		return;
	SDL_LockMutex(player->pcmMutex);
	player->pcm.clear();
	SDL_UnlockMutex(player->pcmMutex);
}

bool CUIVideoBinkDecoder::Init(const char* pathToVideo, bool needSound)
{
	const char* nameOfPlayer = m_aliasName.length() ? m_aliasName.c_str() : pathToVideo;
	unsigned int w, h;

	m_player = CreatePlayerData(pathToVideo);
	if (m_player)
	{
		Bink_GetFrameSize(m_player->binkHandle, w, h);
	
		m_frameBuffer = new uint8[w * h * 4];
		memset(m_frameBuffer, 0, w * h * 4);
		m_textureId = GetISystem()->GetIRenderer()->DownLoadToVideoMemory(m_frameBuffer,
			w, h, eTF_RGBA, eTF_RGBA, 0, 0, FILTER_LINEAR, 0, nullptr, FT_DYNAMIC);
	
		if (m_textureId < 0)
		{
			__builtin_trap();
		}

		if (needSound)
		{
			m_player->numAudioTracks = Bink_GetNumAudioTracks(m_player->binkHandle);

			if(m_player->numAudioTracks > 0)
			{
				m_player->trackIndex = 0;
				m_player->binkInfo = Bink_GetAudioTrackDetails(m_player->binkHandle, m_player->trackIndex);
				if (m_player->binkInfo.idealBufferSize > 0 && m_player->binkInfo.nChannels >= 1 && m_player->binkInfo.nChannels <= 2)
				{
					m_player->pcmMutex = SDL_CreateMutex();
					m_player->audioScratch = new uint8_t[m_player->binkInfo.idealBufferSize];
					m_audioStream = CS_Stream_Create(BinkDecAudioCallback,
						kBinkAudioBlockBytes, CS_STREAM_PULL,
						m_player->binkInfo.sampleRate, m_player);
				}
			}
		}
	}

	return m_player != nullptr;
}

void CUIVideoBinkDecoder::Terminate()
{
	Stop();

	if (m_audioStream)
	{
		CS_Stream_Close(m_audioStream);
	}
	m_audioStream = nullptr;

	if (m_player)
	{
		Bink_Close(m_player->binkHandle);
		if (m_player->pcmMutex)
			SDL_DestroyMutex(m_player->pcmMutex);
		delete[] m_player->audioScratch;
	}
	SAFE_DELETE(m_player);
	SAFE_DELETE_ARRAY(m_frameBuffer);

	if (m_textureId > -1)
	{
		GetISystem()->GetIRenderer()->RemoveTexture(m_textureId);
		m_textureId = -1;
	}
}

void CUIVideoBinkDecoder::Start()
{
	if (!m_player)
	{
		return;
	}
	m_player->startTime = SDL_GetTicks();

	m_playerCmd = PLAYER_CMD_PLAYING;

	if(m_audioStream)
	{
		CS_Stream_Play(CS_FREE, m_audioStream);
	}
}

void CUIVideoBinkDecoder::Stop()
{
	if (!m_player)
		return;

	m_playerCmd = PLAYER_CMD_STOP;

	if (m_audioStream)
		CS_Stream_Stop(m_audioStream);
	BinkClearAudio(m_player);
}

void CUIVideoBinkDecoder::Rewind()
{
	m_playerCmd = PLAYER_CMD_REWIND;
}

bool CUIVideoBinkDecoder::IsPlaying() const
{
	return m_playerCmd != PLAYER_CMD_NONE;
}

void CUIVideoBinkDecoder::BinkDecReset()
{
	m_player->framePos = -1;
	m_player->lastFramePos = -1;
	BinkClearAudio(m_player);

	Bink_GotoFrame( m_player->binkHandle, 0 );
}

void CUIVideoBinkDecoder::DrawYUV(void)
{
	MoviePlayerData* player = m_player;
	if (!player || !m_frameBuffer)
		return;

	int width = player->vidWidth;
	int height = player->vidHeight;
	int yPitch = player->yuvBuffer[0].pitch;
	int uPitch = player->yuvBuffer[1].pitch;
	int vPitch = player->yuvBuffer[2].pitch;
	const uint8_t* yData = player->yuvBuffer[0].data;
	const uint8_t* uData = player->yuvBuffer[1].data;
	const uint8_t* vData = player->yuvBuffer[2].data;

	if (!yData || !uData || !vData)
		return;

	for (int i = 0; i < height; i++)
	{
		uint8_t* destRow = m_frameBuffer + (i * width * 4);
		const uint8_t* yRow = yData + (i * yPitch);
		int si = i / 2;
		const uint8_t* uRow = uData + (si * uPitch);
		const uint8_t* vRow = vData + (si * vPitch);

		for (int j = 0; j < width; j++)
		{
			int sj = j / 2;
			int y = yRow[j];
			int u = uRow[sj] - 128;
			int v = vRow[sj] - 128;

			int r = (int)(y + 1.4075f * v);
			int g = (int)(y - 0.3455f * u - 0.7169f * v);
			int b = (int)(y + 1.7790f * u);

			if (r < 0) r = 0; else if (r > 255) r = 255;
			if (g < 0) g = 0; else if (g > 255) g = 255;
			if (b < 0) b = 0; else if (b > 255) b = 255;

			// RGBA
			destRow[j * 4 + 0] = (uint8_t)r;
			destRow[j * 4 + 1] = (uint8_t)g;
			destRow[j * 4 + 2] = (uint8_t)b;
			destRow[j * 4 + 3] = 255;
		}
	}
}

void CUIVideoBinkDecoder::Present()
{
	MoviePlayerData* player = m_player;
	int thisTime = SDL_GetTicks();
	int desiredFrame;

	if (!player)
	{
		return;
	}

	if( !player->binkHandle.isValid )
	{
		return;
	}

	if((!player->hasFrame) || player->startTime == -1)
	{
		if( player->startTime == -1 )
		{
			BinkDecReset();
		}
		player->startTime = thisTime;
	}

	desiredFrame = ((thisTime - player->startTime) * player->frameRate) / 1000.0f;

	if(desiredFrame < 0)
	{
		desiredFrame = 0;
	}

	if(desiredFrame < player->framePos)
	{
		BinkDecReset();
		player->hasFrame = false;
	}

	if( desiredFrame >= player->numFrames )
	{
		//end of video
		if( player->looping )
		{
			desiredFrame = 0;
			BinkDecReset();
			player->hasFrame = false;
			player->startTime = thisTime;
			m_playerCmd = PLAYER_CMD_PLAYING;
		}
		else
		{
			player->hasFrame = false;
			m_playerCmd = PLAYER_CMD_NONE;
			if (m_onFinished)
			{
				m_onFinished();
			}
			return;
		}
	}

	while(player->framePos < desiredFrame)
	{
		player->framePos = Bink_GetNextFrame(player->binkHandle, player->yuvBuffer);
		if (m_audioStream)
			BinkQueueFrameAudio(player);
	}

	DrawYUV();

	if (m_audioStream)
	{
		CS_Update();
	}

	player->lastFramePos = player->framePos;

	GetISystem()->GetIRenderer()->UpdateTextureInVideoMemory(m_textureId,
		m_frameBuffer, 0, 0, player->vidWidth, player->vidHeight, eTF_RGBA);

	player->hasFrame = true;
}

void CUIVideoBinkDecoder::SetTimeScale(float value)
{
	//STUB
}

int CUIVideoBinkDecoder::GetTextureId() const
{
	return m_textureId;
}

int	CUIVideoBinkDecoder::GetWidth() const
{
	if (!m_player)
	{
		return 1;
	}
	return m_player->vidWidth;
}

int	CUIVideoBinkDecoder::GetHeight() const
{
	if (!m_player)
	{
		return 1;
	}
	return m_player->vidHeight;
}