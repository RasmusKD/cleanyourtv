# Keep class and method names: readable stack traces matter more than the last few KB in a sideloaded app.
-dontobfuscate

# Inject runs through app_process, so nothing in the app references it
-keep class io.github.rasmuskd.remotedaemon.Inject { public static void main(java.lang.String[]); }
