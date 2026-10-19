package com.fongmi.android.tv.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.api.tmdb.TmdbApi;

import org.junit.Test;

public class TmdbApiTest {

    @Test
    public void testCleanTitleEpisodes() {
        assertEquals("复仇者联盟4", TmdbApi.cleanTitle("复仇者联盟4 更新至第3集"));
        assertEquals("三体", TmdbApi.cleanTitle("三体 全30集"));
        assertEquals("狂飙", TmdbApi.cleanTitle("狂飙 共39集"));
        assertEquals("海贼王", TmdbApi.cleanTitle("海贼王 第1000集"));
    }

    @Test
    public void testCleanTitleQualityAndLanguage() {
        assertEquals("流浪地球2", TmdbApi.cleanTitle("流浪地球2 4K/HDR国语版"));
        assertEquals("奥本海默", TmdbApi.cleanTitle("奥本海默 1080P中字"));
        assertEquals("星际穿越", TmdbApi.cleanTitle("星际穿越 BluRay 2160p HDR"));
    }

    @Test
    public void testCleanTitleBracketsAndSuffixes() {
        assertEquals("陈情令", TmdbApi.cleanTitle("陈情令（完结）粤语"));
        assertEquals("庆余年 第二季", TmdbApi.cleanTitle("庆余年 第二季 (已完结)"));
    }

    @Test
    public void testImageUrl() {
        String url = TmdbApi.imageUrl("/abc.jpg", "w780");
        assertNotNull(url);
        assertTrue(url.endsWith("/w780/abc.jpg"));
    }

    @Test
    public void testNormalizeApiBase() {
        assertEquals("https://api.tmdb.org", TmdbApi.normalizeApiBase(null));
        assertEquals("https://api.tmdb.org", TmdbApi.normalizeApiBase(""));
        assertEquals("https://tmdb.example.com", TmdbApi.normalizeApiBase("tmdb.example.com"));
        assertEquals("https://tmdb.example.com", TmdbApi.normalizeApiBase("https://tmdb.example.com/"));
        assertEquals("https://tmdb.example.com", TmdbApi.normalizeApiBase("https://tmdb.example.com/3/"));
        assertEquals("https://tmdb.example.com", TmdbApi.normalizeApiBase("https://tmdb.example.com/3"));
    }

    @Test
    public void testNormalizeImageBase() {
        assertEquals("https://image.tmdb.org/t/p", TmdbApi.normalizeImageBase(null));
        assertEquals("https://image.tmdb.org/t/p", TmdbApi.normalizeImageBase(""));
        assertEquals("https://image.tmdb.org/t/p", TmdbApi.normalizeImageBase("https://image.tmdb.org"));
        assertEquals("https://cdn.example.com/t/p", TmdbApi.normalizeImageBase("cdn.example.com/t/p/"));
    }

    @Test
    public void testBearerTokenDetectionAndExtraction() {
        // v3 32-character hex key
        String v3Key = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
        org.junit.Assert.assertFalse(TmdbApi.isBearerToken(v3Key));

        // v4 JWT Read Access Token (starts with ey...)
        String v4Token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJmMTkyZDFkZWRk...very_long_token";
        org.junit.Assert.assertTrue(TmdbApi.isBearerToken(v4Token));
        assertEquals(v4Token, TmdbApi.extractBearerToken(v4Token));

        // Token with Bearer prefix
        String bearerToken = "Bearer " + v4Token;
        org.junit.Assert.assertTrue(TmdbApi.isBearerToken(bearerToken));
        assertEquals(v4Token, TmdbApi.extractBearerToken(bearerToken));
    }

    @Test
    public void testApplyAuthApiKeyVsToken() {
        okhttp3.HttpUrl.Builder urlBuilder = okhttp3.HttpUrl.parse("https://api.tmdb.org/3/test").newBuilder();
        okhttp3.Request.Builder reqBuilder = new okhttp3.Request.Builder();

        // 1. Test v3 Key: sets query parameter, no Authorization header
        TmdbApi.applyAuth(reqBuilder, urlBuilder, "1234567890abcdef1234567890abcdef");
        okhttp3.Request req1 = reqBuilder.url(urlBuilder.build()).build();
        assertEquals("1234567890abcdef1234567890abcdef", req1.url().queryParameter("api_key"));
        org.junit.Assert.assertNull(req1.header("Authorization"));

        // 2. Test v4 Token: sets Authorization Bearer header, no query parameter
        okhttp3.HttpUrl.Builder tokenUrlBuilder = okhttp3.HttpUrl.parse("https://api.tmdb.org/3/test").newBuilder();
        okhttp3.Request.Builder tokenReqBuilder = new okhttp3.Request.Builder();
        String jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        TmdbApi.applyAuth(tokenReqBuilder, tokenUrlBuilder, jwtToken);
        okhttp3.Request req2 = tokenReqBuilder.url(tokenUrlBuilder.build()).build();
        org.junit.Assert.assertNull(req2.url().queryParameter("api_key"));
        assertEquals("Bearer " + jwtToken, req2.header("Authorization"));
    }
}
