/*
 * jni_md.h for Windows — what jni.h leaves to each system to say: how a
 * function is marked for a DLL to show it, how the JVM calls one, and which
 * C types three of Java's are.
 *
 * A JDK carries this file for the system it runs on and for no other, so the
 * JDK at hand where rahasher.dll is built on another system has none to
 * give. native/build.sh compiles the DLL with this one wherever it is built,
 * Windows included: jni.h, which is the same in every JDK, asks for
 * "jni_md.h" and finds it here.
 *
 * These are facts about Windows and not choices. A long there is 32 bits, on
 * a 64-bit system too, and is what the JVM's own headers call jint;
 * __stdcall is how every function of JNI is called on 32-bit Windows, and
 * says nothing on 64-bit, where there is one way to call.
 */

#ifndef PB_WIN32_JNI_MD_H
#define PB_WIN32_JNI_MD_H

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall

typedef long jint;
typedef long long jlong;
typedef signed char jbyte;

/*
 * Held to, because nothing else would: with a jint of another width the
 * library compiles, loads, and reads its console's number from the wrong
 * bytes. That is this file handed to a compiler for some other system.
 */
#ifndef __cplusplus
_Static_assert(sizeof(jint) == 4 && sizeof(jlong) == 8 && sizeof(jbyte) == 1,
               "native/include/win32/jni_md.h is for Windows, where long is 32 bits");
#endif

#endif /* PB_WIN32_JNI_MD_H */
