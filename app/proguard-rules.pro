# JNI エントリポイントはリネームしない
-keepclasseswithmembernames class com.liquid.lfmomni.engine.LfmOmniNative {
    native <methods>;
}
-keep class com.liquid.lfmomni.engine.TokenCallback { *; }
