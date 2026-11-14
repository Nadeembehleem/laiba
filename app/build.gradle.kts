plugins { id("com.android.application") }
android { namespace = "com.laiba.rebuilt"; compileSdk = 35
 defaultConfig { applicationId = "com.laiba.rebuilt"; minSdk = 26; targetSdk = 35; versionCode = 30; versionName = "30.0-phone-control" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
