package com.edgeai.chatappv2;

/**
 * StringCallBack - Callback to tunnel JNI output into Java
 */
public interface StringCallback {
    void onNewString(String str);
}
