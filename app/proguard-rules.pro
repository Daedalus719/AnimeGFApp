# Keep JS bridge methods
-keepclassmembers class com.daedalus.animegf.MainActivity$BlobDownloadInterface {
    public *;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
