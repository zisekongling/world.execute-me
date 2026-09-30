# The JavaScript bridge is called reflectively and must not be renamed away.
-keepclassmembers class com.worldexecute.viewer.PlayerActivity$AndroidHost {
    public *;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*
