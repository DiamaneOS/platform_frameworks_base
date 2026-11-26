/* Copyright (C) 2026 DiamaneOS */
#pragma once

namespace android {

// The Runnable holds only weak drawable and registration references and posts to their saved looper.
class GatedAnimationCallback final : public OnAnimationEndListener {
public:
    GatedAnimationCallback(JNIEnv* env, jobject callback) {
        LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&mJvm) != JNI_OK);
        mCallback = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        mRun = env->GetMethodID(cls, "run", "()V");
        env->DeleteLocalRef(cls);
    }

    ~GatedAnimationCallback() override { requireEnv(mJvm)->DeleteGlobalRef(mCallback); }

    void onAnimationEnd() override { requireEnv(mJvm)->CallVoidMethod(mCallback, mRun); }

private:
    JavaVM* mJvm;
    jobject mCallback;
    jmethodID mRun;
};

}  // namespace android
