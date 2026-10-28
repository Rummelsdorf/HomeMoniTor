localWebServer = LocalWebServer.get() ?: LocalWebServer.init(
            LOCAL_PORT,
            { cam -> latestJpeg },
            { sensorManager.collectJson() },
            okHttpClient
        )