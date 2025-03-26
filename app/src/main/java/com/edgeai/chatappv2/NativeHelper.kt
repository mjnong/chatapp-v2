package com.edgeai.chatappv2;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * NativeHelper: A utility class to bridge Kotlin and native code for environment settings
 */
object NativeHelper {
    private const val TAG = "NativeHelper";
    private var libraryLoaded = false;

    init {
        try {
            System.loadLibrary("native-helper");
            libraryLoaded = true;
            Log.i(TAG, "Loaded native-helper library");
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native-helper library: ${e.message}");
        }
    }

    /**
     * Sets the ADSP_LIBRARY_PATH environment variable to point to the jniLibs folder
     * using multiple fallback approaches.
     *
     * @param context Android context
     * @return true if the environment variable was set successfully, false otherwise
     */
    fun setAdspLibraryPath(context: Context): Boolean {
        return try {
            // Get native library path
            val pm = context.packageManager;
            val packageName = context.packageName;
            val nativeLibPath = pm.getApplicationInfo(packageName, 0).nativeLibraryDir;
            
            Log.i(TAG, "Native library path: $nativeLibPath");
            
            // Attempt to set environment variable using native method
            var success = false;
            if (libraryLoaded) {
                success = setEnvNative("ADSP_LIBRARY_PATH", nativeLibPath);
                Log.i(TAG, "Setting ADSP_LIBRARY_PATH via JNI: ${if (success) "success" else "failed"}");
            }
            
            // Also set as system property as backup
            System.setProperty("ADSP_LIBRARY_PATH", nativeLibPath);
            
            // Write path to a file that can be read by native code
            writePathToFile(context, nativeLibPath);
            
            true;
        } catch (e: Exception) {
            Log.e(TAG, "Error setting ADSP_LIBRARY_PATH: ${e.message}");
            false;
        }
    }
    
    /**
     * Write the native library path to a file that can be read by native code
     */
    private fun writePathToFile(context: Context, path: String) {
        try {
            // Write to internal app file
            val file = File(context.filesDir, "adsp_library_path.txt");
            FileOutputStream(file).use { fos ->
                fos.write(path.toByteArray());
            };
            
            Log.i(TAG, "Wrote ADSP_LIBRARY_PATH to: ${file.absolutePath}");
            
            // Also attempt to write to a known location that native code might check
            context.getExternalFilesDir(null)?.let { extDir ->
                val extFile = File(extDir, "adsp_library_path.txt");
                FileOutputStream(extFile).use { extFos ->
                    extFos.write(path.toByteArray());
                };
                Log.i(TAG, "Wrote ADSP_LIBRARY_PATH to external: ${extFile.absolutePath}");
            };
        } catch (e: IOException) {
            Log.e(TAG, "Error writing ADSP_LIBRARY_PATH to file: ${e.message}");
        }
    }
    
    /**
     * Get the current ADSP_LIBRARY_PATH environment variable value
     *
     * @return The current value or null if not set
     */
    fun getAdspLibraryPath(): String? {
        if (libraryLoaded) {
            val path = getEnvNative("ADSP_LIBRARY_PATH");
            if (!path.isNullOrEmpty()) {
                return path;
            }
        }
        
        // Fall back to system property
        return System.getProperty("ADSP_LIBRARY_PATH");
    }
    
    /**
     * Verify if ADSP_LIBRARY_PATH is set correctly and accessible from native code
     * This provides a more reliable check than just verifying the Kotlin side setting
     * 
     * @return true if the environment variable is set and accessible, false otherwise
     */
    fun verifyAdspLibraryPathFromNative(): Boolean {
        return libraryLoaded && verifyAdspLibraryPathNative();
    }
    
    /**
     * Comprehensively verify ADSP_LIBRARY_PATH from both Kotlin and native sides
     * 
     * @return A verification result with details about the success or failure
     */
    fun verifyAdspLibraryPathComprehensive(): AdspVerificationResult {
        val result = AdspVerificationResult();
        
        // Check Kotlin side
        val kotlinPath = getAdspLibraryPath();
        result.setFromJava(kotlinPath != null && kotlinPath.isNotEmpty(), kotlinPath);
        
        // Check native side
        if (libraryLoaded) {
            val nativeResult = verifyAdspLibraryPathNative();
            result.setFromNative(nativeResult);
        } else {
            result.setFromNative(false);
        }
        
        return result;
    }
    
    /**
     * A class to hold the results of ADSP_LIBRARY_PATH verification
     */
    class AdspVerificationResult {
        private var setFromJava = false;
        private var setFromNative = false;
        private var path: String? = null;
        
        fun setFromJava(isSet: Boolean, path: String?) {
            this.setFromJava = isSet;
            this.path = path;
        }
        
        fun setFromNative(isSet: Boolean) {
            this.setFromNative = isSet;
        }
        
        fun isSetFromJava(): Boolean = setFromJava;
        
        fun isSetFromNative(): Boolean = setFromNative;
        
        fun isFullyVerified(): Boolean = setFromJava && setFromNative;
        
        fun getPath(): String? = path;
        
        override fun toString(): String {
            return "ADSP_LIBRARY_PATH verification:" +
                    "\n  Kotlin side: ${if (setFromJava) "✅ Set" else "❌ Not set"}" +
                    "\n  Native side: ${if (setFromNative) "✅ Set" else "❌ Not set"}" +
                    "\n  Path: ${path ?: "null"}"
        }
    }

    /**
     * Print diagnostic information about environment variables from native code
     * This will log all environment variables related to library loading
     */
    fun printDiagnosticInfo() {
        Log.i(TAG, "===== Environment Variable Diagnostics =====");
        
        // Print from Kotlin side
        Log.i(TAG, "Kotlin side ADSP_LIBRARY_PATH: ${System.getProperty("ADSP_LIBRARY_PATH")}");
        
        // Print from native side
        if (libraryLoaded) {
            printDiagnosticInfoNative();
        } else {
            Log.i(TAG, "Native library not loaded, can't print native environment variables");
        }
        
        Log.i(TAG, "===== End of Environment Variable Diagnostics =====");
    }
    
    // Native methods
    private external fun setEnvNative(name: String, value: String): Boolean;
    private external fun getEnvNative(name: String): String?;
    private external fun verifyAdspLibraryPathNative(): Boolean;
    private external fun printDiagnosticInfoNative();
} 