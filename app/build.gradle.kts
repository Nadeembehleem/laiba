plugins { id("com.android.application") }
android { namespace = "com.laiba.rebuilt"; compileSdk = 35
 defaultConfig { applicationId = "com.laiba.rebuilt"; minSdk = 26; targetSdk = 35; versionCode = 32; versionName = "31.1-elevenlabs" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
