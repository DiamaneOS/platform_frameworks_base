/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "Scrypt"

#include <nativehelper/JNIHelp.h>
#include "jni.h"

#include <android_runtime/Log.h>
#include <utils/Timers.h>
#include <utils/misc.h>
#include <utils/String8.h>
#include <utils/Log.h>

#include <errno.h>
#include <string.h>

extern "C" {
#include "crypto_scrypt.h"
}

namespace android {

static jbyteArray android_security_Scrypt_nativeScrypt(JNIEnv* env, jobject, jbyteArray password, jbyteArray salt, jint N, jint r, jint p, jint outLen) {
    if (!password || !salt) {
        return NULL;
    }

    int passwordLen = env->GetArrayLength(password);
    int saltLen = env->GetArrayLength(salt);
    jbyteArray ret = env->NewByteArray(outLen);

    // Each of these can fail, which leaves an exception pending.  Stop at the first failure, since
    // most JNI functions must not be called with an exception pending.
    jbyte* passwordPtr = ret ? (jbyte*)env->GetByteArrayElements(password, NULL) : NULL;
    jbyte* saltPtr = passwordPtr ? (jbyte*)env->GetByteArrayElements(salt, NULL) : NULL;
    jbyte* retPtr = saltPtr ? (jbyte*)env->GetByteArrayElements(ret, NULL) : NULL;

    int rc = -1;
    int err = ENOMEM;
    if (retPtr) {
        // This fails with ENOMEM if the 128 * r * N bytes of working memory can't be mapped.
        rc = crypto_scrypt((const uint8_t *)passwordPtr, passwordLen,
                           (const uint8_t *)saltPtr, saltLen, N, r, p, (uint8_t *)retPtr,
                           outLen);
        err = errno;
    }
    if (passwordPtr) env->ReleaseByteArrayElements(password, passwordPtr, JNI_ABORT);
    if (saltPtr) env->ReleaseByteArrayElements(salt, saltPtr, JNI_ABORT);
    if (retPtr) env->ReleaseByteArrayElements(ret, retPtr, 0);

    if (!rc) {
        return ret;
    } else {
        // Report every failure the same way: a null result that the caller must check, with no
        // exception left pending.
        env->ExceptionClear();
        SLOGE("scrypt failed: %s", strerror(err));
        return NULL;
    }
}

static const JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"nativeScrypt", "([B[BIIII)[B", (void*)android_security_Scrypt_nativeScrypt},
};

int register_android_security_Scrypt(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "android/security/Scrypt",
                                    sMethods, NELEM(sMethods));
}

} /* namespace android */
