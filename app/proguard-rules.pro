# Keep JS bridge methods
-keepclassmembers class com.piotr.animegf.MainActivity$BlobDownloadInterface {
    public *;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
