// mxv2 - 小窓（ピクチャー・イン・ピクチャー。Android）

#include "pip.h"

#ifdef __ANDROID__

#include <cstdio>

#include <jni.h>

#include <SDL.h>

namespace mxv2 {
namespace pip {

namespace {

// Java 側 (net.gorry.mxv2.PipBridge) の窓口。
// **メインスレッドからだけ呼ぶこと。** FindClass はスレッドのクラスローダを
// 使うので、SDL が後から attach した作業スレッドからではアプリのクラスが
// 見えない（nowplaying.cpp と同じ事情）。
struct PipJni {
	bool tried;
	bool ok;
	jclass cls;
	jmethodID available;
	jmethodID setMode;
	jmethodID enter;
	jmethodID inPip;
};

PipJni g = { false, false, 0, 0, 0, 0, 0 };

// available() の答え。端末が変わることは無いので 1 回だけ尋ねる。
int g_available = -1;

JNIEnv *Env() {
	return (JNIEnv *)SDL_AndroidGetJNIEnv();
}

bool EnsureJni() {
	if (g.tried) return g.ok;
	g.tried = true;

	JNIEnv *env = Env();
	if (env == 0) return false;

	jclass local = env->FindClass("net/gorry/mxv2/PipBridge");
	if (local == 0) {
		env->ExceptionClear();
		printf("warning  : PipBridge class not found (no picture-in-picture)\n");
		return false;
	}
	g.cls = (jclass)env->NewGlobalRef(local);
	env->DeleteLocalRef(local);

	g.available = env->GetStaticMethodID(g.cls, "available", "()Z");
	g.setMode = env->GetStaticMethodID(g.cls, "setMode", "(I)V");
	g.enter = env->GetStaticMethodID(g.cls, "enter", "()V");
	g.inPip = env->GetStaticMethodID(g.cls, "inPip", "()Z");

	g.ok = (g.available != 0 && g.setMode != 0 && g.enter != 0 && g.inPip != 0);
	if (!g.ok) {
		env->ExceptionClear();
		printf("warning  : PipBridge methods not found (no picture-in-picture)\n");
	}
	return g.ok;
}

}  // namespace

bool Available() {
	if (g_available >= 0) return g_available != 0;
	if (!EnsureJni()) {
		g_available = 0;
		return false;
	}
	g_available = (Env()->CallStaticBooleanMethod(g.cls, g.available) != JNI_FALSE) ? 1 : 0;
	return g_available != 0;
}

void SetMode(int mode) {
	if (!Available()) return;
	Env()->CallStaticVoidMethod(g.cls, g.setMode, (jint)mode);
}

void Enter() {
	if (!Available()) return;
	Env()->CallStaticVoidMethod(g.cls, g.enter);
}

bool Active() {
	if (!Available()) return false;
	return Env()->CallStaticBooleanMethod(g.cls, g.inPip) != JNI_FALSE;
}

}  // namespace pip
}  // namespace mxv2

#else  // __ANDROID__

namespace mxv2 {
namespace pip {

bool Available() {
	return false;
}

void SetMode(int mode) {
	(void)mode;
}

void Enter() {}

bool Active() {
	return false;
}

}  // namespace pip
}  // namespace mxv2

#endif  // __ANDROID__
