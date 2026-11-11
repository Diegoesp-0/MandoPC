plugins { id("com.android.application") }

android {
    namespace = "com.mandopc"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mandopc"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }
}

dependencies { testImplementation("junit:junit:4.13.2") }
