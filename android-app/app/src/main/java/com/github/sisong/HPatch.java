package com.github.sisong;

/**
 * JNI contract for HDiffPatch v5.1.3's official Android libhpatchz.so.
 * Upstream: https://github.com/sisong/HDiffPatch/tree/v5.1.3/builds/android_ndk_jni_mk
 * The native library is retrieved from the pinned upstream Android SDK during CI builds.
 */
public final class HPatch {
    private HPatch() {}
    static { System.loadLibrary("hpatchz"); }

    /** Returns zero on success; a nonzero native error code otherwise. */
    public static native int patch(String oldFileName, String diffFileName,
                                   String outNewFileName, long cacheMemory,
                                   int threadNum, boolean isChecksumNewData);
}
