package com.fongmi.android.tv.api.loader;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;

public class PyLoader {

    public PyLoader() {
    }

    public void clear() {
    }

    public void setRecent(String recent) {
    }

    public Spider getSpider(String key, String api, String ext) {
        return new SpiderNull();
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        return null;
    }
}
