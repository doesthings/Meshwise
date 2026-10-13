// String conversion between Java (UTF-16) and the UTF-8 used by the native code.
// JNI's *StringUTF* functions use "modified UTF-8", which differs for supplementary
// characters (emoji) and rejects malformed input, so both directions are done by hand.
#pragma once

#include <jni.h>

#include <cstdint>
#include <string>

namespace fsvjni {

inline std::string toUtf8(JNIEnv* env, jstring s) {
    if (!s) return std::string();
    const jsize n = env->GetStringLength(s);
    const jchar* c = env->GetStringChars(s, nullptr);
    if (!c) return std::string();
    std::string out;
    out.reserve((size_t)n);
    for (jsize i = 0; i < n; ++i) {
        uint32_t cp = c[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n && c[i + 1] >= 0xDC00 && c[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (c[i + 1] - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp < 0x80) {
            out.push_back((char)cp);
        } else if (cp < 0x800) {
            out.push_back((char)(0xC0 | (cp >> 6)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back((char)(0xE0 | (cp >> 12)));
            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else {
            out.push_back((char)(0xF0 | (cp >> 18)));
            out.push_back((char)(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(s, c);
    return out;
}

// Builds a Java string from (possibly malformed) UTF-8 found in user files. NewStringUTF
// expects modified UTF-8 and aborts under CheckJNI on anything else, so decode by hand;
// invalid bytes become U+FFFD and 4-byte sequences become surrogate pairs.
inline jstring newString(JNIEnv* env, const std::string& s) {
    std::u16string out;
    out.reserve(s.size());
    const auto* p = reinterpret_cast<const unsigned char*>(s.data());
    const size_t n = s.size();
    for (size_t i = 0; i < n;) {
        unsigned char c = p[i];
        uint32_t cp = 0xFFFD;
        size_t len = 1;
        if (c < 0x80) cp = c;
        else if ((c & 0xE0) == 0xC0) len = 2, cp = c & 0x1F;
        else if ((c & 0xF0) == 0xE0) len = 3, cp = c & 0x0F;
        else if ((c & 0xF8) == 0xF0) len = 4, cp = c & 0x07;
        else len = 0;
        if (len > 1) {
            bool ok = i + len <= n;
            for (size_t k = 1; ok && k < len; ++k) {
                if ((p[i + k] & 0xC0) != 0x80) ok = false;
                else cp = (cp << 6) | (p[i + k] & 0x3F);
            }
            static const uint32_t minCp[5] = {0, 0, 0x80, 0x800, 0x10000};
            if (!ok || cp < minCp[len] || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) cp = 0xFFFD, len = 1;
        } else if (len == 0) {
            len = 1;
        }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back((char16_t)(0xD800 + (cp >> 10)));
            out.push_back((char16_t)(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back((char16_t)cp);
        }
        i += len;
    }
    return env->NewString(reinterpret_cast<const jchar*>(out.data()), (jsize)out.size());
}


// Throws a new exception of class `cls` with a message that may contain arbitrary bytes.
inline void throwJava(JNIEnv* env, const char* cls, const std::string& msg) {
    if (env->ExceptionCheck()) return;
    jclass c = env->FindClass(cls);
    if (!c) return;
    jmethodID ctor = env->GetMethodID(c, "<init>", "(Ljava/lang/String;)V");
    if (!ctor) {
        env->ExceptionClear();
        env->ThrowNew(c, "error");
        return;
    }
    auto ex = static_cast<jthrowable>(env->NewObject(c, ctor, newString(env, msg)));
    if (ex) env->Throw(ex);
}

}  // namespace fsvjni
