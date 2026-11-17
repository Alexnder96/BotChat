package com.botchat.app;

import android.app.Application;

import com.botchat.app.data.Config;
import com.botchat.app.filters.Filters;

/** Carga la configuración y los filtros guardados en cuanto arranca la app. */
public class BotApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Config.load(this);
        Filters.load(this);
    }
}
