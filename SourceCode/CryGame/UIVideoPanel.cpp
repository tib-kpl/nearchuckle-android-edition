
//////////////////////////////////////////////////////////////////////
//
//	Crytek Source code 
//	Copyright (c) Crytek 2001-2004
//
//  File: UIVideoPanel.cpp
//  Description: UI Video Panel Manager
//
//  History:
//  - [9/7/2003]: File created by Marcio Martins
//	- February 2005: Modified by Marco Corbetta for SDK release
//
//////////////////////////////////////////////////////////////////////

#include "StdAfx.h"
#include <stdlib.h>
#include "UIVideoPanel.h"
#include "UISystem.h"

_DECLARE_SCRIPTABLEEX(CUIVideoPanel)

////////////////////////////////////////////////////////////////////// 
CUIVideoPanel::CUIVideoPanel()
	: m_pSwapBuffer(0), m_szVideoFile(""), m_bKeepAspect(1), m_bLooping(0), m_bFinished(false), m_bPaused(false)
{
	m_cColor = color4f(0.0f, 0.0f, 0.0f, 1.0f);
}

////////////////////////////////////////////////////////////////////// 
CUIVideoPanel::~CUIVideoPanel()
{
	ReleaseVideo();
}

////////////////////////////////////////////////////////////////////// 
string CUIVideoPanel::GetClassName()
{
	return UICLASSNAME_VIDEOPANEL;
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::LoadVideo(const string& szFileName, bool bSound)
{
	m_videoPlayer.Terminate();
	if (!m_videoPlayer.Init(szFileName.c_str(), bSound))
	{
		return 0;
	}

	m_szVideoFile = szFileName;
	m_bFinished = false;
	m_bPaused = false;
	m_videoPlayer.m_onFinished = [this]() {
		if (m_bLooping)
			m_videoPlayer.Rewind();
		else
			m_bFinished = true;
	};
	return 1;
}

////////////////////////////////////////////////////////////////////// 
LRESULT CUIVideoPanel::Update(unsigned int iMessage, WPARAM wParam, LPARAM lParam)	//AMD Port
{
	FUNCTION_PROFILER(m_pUISystem->GetISystem(), PROFILE_GAME);

	// update texture
	m_videoPlayer.Present();

	if (m_bFinished)
	{
		m_bFinished = false;
		if (m_bLooping)
		{
			m_videoPlayer.Rewind();
		}
		else
		{
			Stop();
			OnFinished();
			return 1;
		}
	}

	return CUISystem::DefaultUpdate(this, iMessage, wParam, lParam);
}


////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::Play()
{
	if (m_videoPlayer.GetTextureId() == -1)
		return 0;
	m_bFinished = false;
	m_videoPlayer.Start();
	m_bPaused = false;
	return 1;
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::Stop()
{
	m_bFinished = false;
	m_videoPlayer.Stop();
	m_videoPlayer.Terminate();
	return 1;
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::ReleaseVideo()
{
	m_videoPlayer.Terminate();
	return 1;
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::Pause(bool bPause)
{
	m_bPaused = bPause;
	if (bPause)
		m_videoPlayer.Stop();
	else
		m_videoPlayer.Start();
	return 1;
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::IsPlaying()
{
	return !m_bFinished;
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::IsPaused()
{
	return m_bPaused;
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::SetVolume(int iTrackID, float fVolume)
{
	return 1;
}

//////////////////////////////////////////////////////////////////////
int CUIVideoPanel::SetPan(int iTrackID, float fPan)
{
	return 1;
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::SetFrameRate(int iFrameRate)
{
	m_videoPlayer.SetTimeScale(1);	// TODO: figure out, maybe not needed
	return 1;
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::Draw(int iPass)
{
	if (iPass != 0)
	{
		return 1;
	}

	m_pUISystem->BeginDraw(this);

	// get the absolute widget rect
	UIRect pAbsoluteRect(m_pRect);

	m_pUISystem->GetAbsoluteXY(&pAbsoluteRect.fLeft, &pAbsoluteRect.fTop, m_pRect.fLeft, m_pRect.fTop, m_pParent);

	// if transparent, draw only the clipped text
	if ((GetStyle() & UISTYLE_TRANSPARENT) == 0)
	{
		// if shadowed, draw the shadow
		if (GetStyle() & UISTYLE_SHADOWED)
		{
			m_pUISystem->DrawShadow(pAbsoluteRect, UI_DEFAULT_SHADOW_COLOR, UI_DEFAULT_SHADOW_BORDER_SIZE, this);
		}
	}

	// if border is large enough to be visible, draw it
	if (m_pBorder.fSize > 0.125f)
	{
		m_pUISystem->DrawBorder(pAbsoluteRect, m_pBorder);
		m_pUISystem->AdjustRect(&pAbsoluteRect, pAbsoluteRect, m_pBorder.fSize);
	}

	// save the client area without the border,
	// to draw a greyed quad later, if disabled
	UIRect pGreyedRect = pAbsoluteRect;

	// video
	const int textureId = m_videoPlayer.GetTextureId();
	if (textureId > -1)
	{
		float fWidth = pAbsoluteRect.fWidth;
		float fHeight = pAbsoluteRect.fHeight;

		// How the video fills the panel: the launcher's choice (FC_VIDEO_FIT: 0 automatic, 1 original
		// ratio, 2 fill and crop, 3 stretch). Automatic: a looping video (the menu's background)
		// fills the screen cropped, the others (intros) are stretched to it.
		int fit = 0;
		if (const char *pFit = getenv("FC_VIDEO_FIT"))
			fit = atoi(pFit);
		if (fit <= 0 || fit > 3)
			fit = m_bLooping ? 2 : 3;
		else if (fit == 1)
			fit = m_bKeepAspect ? 1 : 3;
		const bool bCover = (fit == 2);

		if (fit != 3)
		{
			IRenderer *rend = m_pUISystem->GetIRenderer();
			float vw = (float)m_videoPlayer.GetWidth();
			float vh = (float)m_videoPlayer.GetHeight();
			float rw = rend ? (float)rend->GetWidth() : 800.0f;
			float rh = rend ? (float)rend->GetHeight() : 600.0f;

			if (vw > 0.0f && vh > 0.0f && rw > 0.0f && rh > 0.0f)
			{
				float phys_rect_w = pAbsoluteRect.fWidth * (rw / 800.0f);
				float phys_rect_h = pAbsoluteRect.fHeight * (rh / 600.0f);
				float phys_rect_aspect = phys_rect_w / phys_rect_h;
				float video_aspect = vw / vh;

				float phys_w, phys_h;
				if ((phys_rect_aspect > video_aspect) != bCover)
				{
					phys_h = phys_rect_h;
					phys_w = phys_rect_h * video_aspect;
				}
				else
				{
					phys_w = phys_rect_w;
					phys_h = phys_rect_w / video_aspect;
				}
				fWidth = phys_w * (800.0f / rw);
				fHeight = phys_h * (600.0f / rh);
			}
		}

		UIRect pRect;
		pRect.fLeft = pAbsoluteRect.fLeft + (pAbsoluteRect.fWidth - fWidth) * 0.5f;
		pRect.fTop = pAbsoluteRect.fTop + (pAbsoluteRect.fHeight - fHeight) * 0.5f;
		pRect.fWidth = fWidth;
		pRect.fHeight = fHeight;

		if (fit == 1)
		{
			m_pUISystem->DrawQuad(pAbsoluteRect, m_cColor);
		}

		m_pUISystem->DrawImage(pRect, textureId, 0, color4f(1.0f, 1.0f, 1.0f, 1.0f));
	}

	// draw overlay
	if (m_pOverlay.iTextureID > -1)
	{
		m_pUISystem->DrawSkin(pAbsoluteRect, m_pOverlay, color4f(1.0f, 1.0f, 1.0f, 1.0f), UISTATE_UP);
	}

	// draw a greyed quad ontop, if disabled
	if ((m_iFlags & UIFLAG_ENABLED) == 0)
	{
		m_pUISystem->ResetDraw();
		m_pUISystem->DrawGreyedQuad(pGreyedRect, m_cGreyedColor, m_iGreyedBlend);
	}

	m_pUISystem->EndDraw();

	// draw the children
	if (m_pUISystem->ShouldSortByZ())
	{
		SortChildrenByZ();
	}

	DrawChildren();

	return 1;
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::EnableVideo(bool bEnable)
{
#if !defined(WIN64) && !defined(LINUX) && !defined(NOT_USE_BINK_SDK)
	if (!m_hBink)
	{
		return 0;
	}

	return BinkSetVideoOnOff(m_hBink, bEnable ? 1 : 0);
#else
	return 0;
#endif

	return 0;
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::EnableAudio(bool bEnable)
{
#if !defined(WIN64) && !defined(LINUX) && !defined(NOT_USE_BINK_SDK)

	if (!m_hBink)
	{
		return 0;
	}

	return BinkSetSoundOnOff(m_hBink, bEnable ? 1 : 0);
#else
	return 0;
#endif

	return 0;
}

////////////////////////////////////////////////////////////////////// 
void CUIVideoPanel::InitializeTemplate(IScriptSystem *pScriptSystem)
{
	_ScriptableEx<CUIVideoPanel>::InitializeTemplate(pScriptSystem);

	REGISTER_COMMON_MEMBERS(pScriptSystem, CUIVideoPanel);

	REGISTER_SCRIPTOBJECT_MEMBER(pScriptSystem, CUIVideoPanel, LoadVideo);
	REGISTER_SCRIPTOBJECT_MEMBER(pScriptSystem, CUIVideoPanel, ReleaseVideo);
	REGISTER_SCRIPTOBJECT_MEMBER(pScriptSystem, CUIVideoPanel, Play);
	REGISTER_SCRIPTOBJECT_MEMBER(pScriptSystem, CUIVideoPanel, Stop);
	REGISTER_SCRIPTOBJECT_MEMBER(pScriptSystem, CUIVideoPanel, Pause);
	REGISTER_SCRIPTOBJECT_MEMBER(pScriptSystem, CUIVideoPanel, IsPlaying);
	REGISTER_SCRIPTOBJECT_MEMBER(pScriptSystem, CUIVideoPanel, IsPaused);
	REGISTER_SCRIPTOBJECT_MEMBER(pScriptSystem, CUIVideoPanel, SetVolume);
	REGISTER_SCRIPTOBJECT_MEMBER(pScriptSystem, CUIVideoPanel, SetPan);
	REGISTER_SCRIPTOBJECT_MEMBER(pScriptSystem, CUIVideoPanel, SetFrameRate);
	REGISTER_SCRIPTOBJECT_MEMBER(pScriptSystem, CUIVideoPanel, EnableVideo);
	REGISTER_SCRIPTOBJECT_MEMBER(pScriptSystem, CUIVideoPanel, EnableAudio);
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::OnError(const char *szError)
{
	IScriptSystem *pScriptSystem = m_pUISystem->GetIScriptSystem();
	IScriptObject *pScriptObject = m_pUISystem->GetWidgetScriptObject(this);

	if (!pScriptObject)
	{
		return 1;
	}

	HSCRIPTFUNCTION pScriptFunction = pScriptSystem->GetFunctionPtr(GetName().c_str(), "OnError");

	if (!pScriptFunction)
	{
		if (!pScriptObject->GetValue("OnError", pScriptFunction))
		{
			return 1;
		}
	}

	int iResult = 1;

	pScriptSystem->BeginCall(pScriptFunction);
	pScriptSystem->PushFuncParam(pScriptObject);
	pScriptSystem->PushFuncParam(szError);
	pScriptSystem->EndCall(iResult);

	pScriptSystem->ReleaseFunc(pScriptFunction);

	return iResult;
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::OnFinished()
{
	IScriptSystem *pScriptSystem = m_pUISystem->GetIScriptSystem();
	IScriptObject *pScriptObject = m_pUISystem->GetWidgetScriptObject(this);

	if (!pScriptObject)
	{
		return 1;
	}

	HSCRIPTFUNCTION pScriptFunction = pScriptSystem->GetFunctionPtr(GetName().c_str(), "OnFinished");

	if (!pScriptFunction)
	{
		if (!pScriptObject->GetValue("OnFinished", pScriptFunction))
		{
			return 1;
		}
	}

	int iResult = 1;

	pScriptSystem->BeginCall(pScriptFunction);
	pScriptSystem->PushFuncParam(pScriptObject);
	pScriptSystem->EndCall(iResult);

	pScriptSystem->ReleaseFunc(pScriptFunction);

	return iResult;
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::LoadVideo(IFunctionHandler *pH)
{
	CHECK_SCRIPT_FUNCTION_PARAMCOUNT2(m_pScriptSystem, GetName().c_str(), LoadVideo, 1, 2);
	CHECK_SCRIPT_FUNCTION_PARAMTYPE(m_pScriptSystem, GetName().c_str(), LoadVideo, 1, svtString);

	if (pH->GetParamCount() == 2)
	{
		CHECK_SCRIPT_FUNCTION_PARAMTYPE(m_pScriptSystem, GetName().c_str(), LoadVideo, 2, svtNumber);
	}
	
	char *pszFileName;
	int iSound = 0;

	pH->GetParam(1, pszFileName);

	if (pH->GetParamCount() == 2)
	{
		pH->GetParam(2, iSound);
	}

	if (!LoadVideo(pszFileName, iSound != 0))
	{
		return pH->EndFunctionNull();
	}

	return pH->EndFunction(1);
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::ReleaseVideo(IFunctionHandler *pH)
{
	CHECK_SCRIPT_FUNCTION_PARAMCOUNT(m_pScriptSystem, GetName().c_str(), ReleaseVideo, 0);

	ReleaseVideo();

	return pH->EndFunction(1);
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::Play(IFunctionHandler *pH)
{
	CHECK_SCRIPT_FUNCTION_PARAMCOUNT(m_pScriptSystem, GetName().c_str(), Play, 0);

	Play();

	return pH->EndFunction(1);
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::Stop(IFunctionHandler *pH)
{
	CHECK_SCRIPT_FUNCTION_PARAMCOUNT(m_pScriptSystem, GetName().c_str(), Stop, 0);

	Stop();

	return pH->EndFunction(1);
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::Pause(IFunctionHandler *pH)
{
	CHECK_SCRIPT_FUNCTION_PARAMCOUNT(m_pScriptSystem, GetName().c_str(), Pause, 1);
	CHECK_SCRIPT_FUNCTION_PARAMTYPE(m_pScriptSystem, GetName().c_str(), Pause, 1, svtNumber);

	int iPause;

	pH->GetParam(1, iPause);

	Pause(iPause != 0);

	return pH->EndFunction(1);
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::IsPlaying(IFunctionHandler *pH)
{
	CHECK_SCRIPT_FUNCTION_PARAMCOUNT(m_pScriptSystem, GetName().c_str(), IsPlaying, 0);

	if (IsPlaying())
		return pH->EndFunction(1);
	else
		return pH->EndFunctionNull();
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::IsPaused(IFunctionHandler *pH)
{
	CHECK_SCRIPT_FUNCTION_PARAMCOUNT(m_pScriptSystem, GetName().c_str(), IsPaused, 0);

	if (m_bPaused)
	{
		return pH->EndFunction(1);
	}
	else
	{
		return pH->EndFunctionNull();
	}
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::SetVolume(IFunctionHandler *pH)
{
	CHECK_SCRIPT_FUNCTION_PARAMCOUNT(m_pScriptSystem, GetName().c_str(), SetVolume, 1);
	CHECK_SCRIPT_FUNCTION_PARAMTYPE(m_pScriptSystem, GetName().c_str(), SetVolume, 1, svtNumber);

	float fVolume;

	pH->GetParam(1, fVolume);

	for (int i = 0; i < 16; i++)
	{
		SetVolume(i, fVolume);
	}

	return pH->EndFunction(1);
}

//////////////////////////////////////////////////////////////////////
int CUIVideoPanel::SetPan(IFunctionHandler *pH)
{
	CHECK_SCRIPT_FUNCTION_PARAMCOUNT(m_pScriptSystem, GetName().c_str(), SetPan, 1);
	CHECK_SCRIPT_FUNCTION_PARAMTYPE(m_pScriptSystem, GetName().c_str(), SetPan, 1, svtNumber);

	float fPan;

	pH->GetParam(1, fPan);

	for (int i = 0; i < 16; i++)
	{
		SetPan(i, fPan);
	}

	return pH->EndFunction(1);
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::SetFrameRate(IFunctionHandler *pH)
{
	CHECK_SCRIPT_FUNCTION_PARAMCOUNT(m_pScriptSystem, GetName().c_str(), SetFrameRate, 1);
	CHECK_SCRIPT_FUNCTION_PARAMTYPE(m_pScriptSystem, GetName().c_str(), SetFrameRate, 1, svtNumber);

	int iFrameRate;

	pH->GetParam(1, iFrameRate);

	SetFrameRate(iFrameRate);

	return pH->EndFunction(1);
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::EnableVideo(IFunctionHandler *pH)
{
	CHECK_SCRIPT_FUNCTION_PARAMCOUNT(m_pScriptSystem, GetName().c_str(), EnableVideo, 1);
	CHECK_SCRIPT_FUNCTION_PARAMTYPE(m_pScriptSystem, GetName().c_str(), EnableVideo, 1, svtNumber);

	int iEnable;

	pH->GetParam(1, iEnable);

	EnableVideo(iEnable != 0);

	return pH->EndFunction(1);
}

////////////////////////////////////////////////////////////////////// 
int CUIVideoPanel::EnableAudio(IFunctionHandler *pH)
{
	CHECK_SCRIPT_FUNCTION_PARAMCOUNT(m_pScriptSystem, GetName().c_str(), EnableAudio, 1);
	CHECK_SCRIPT_FUNCTION_PARAMTYPE(m_pScriptSystem, GetName().c_str(), EnableAudio, 1, svtNumber);

	int iEnable;

	pH->GetParam(1, iEnable);

	EnableAudio(iEnable != 0);
	
	return pH->EndFunction(1);
}
