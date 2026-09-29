const fs = require("fs");
const path = require("path");
function row(o) {
  const divider = o.divider ? `                        <View
                            android:layout_width="match_parent"
                            android:layout_height="0.6dp"
                            android:layout_marginStart="16dp"
                            android:background="#EFEFEF" />

` : "";
  const vis = o.gone ? `
                        android:visibility="gone"` : "";
  const end = o.times ? "" : `
                                android:layout_marginEnd="6dp"`;
  const tools = o.tools ? `
                                tools:text="${o.tools}"` : "";
  const times = o.times ? `                            <com.google.android.material.textview.MaterialTextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:layout_marginStart="4dp"
                                android:layout_marginEnd="6dp"
                                android:text="@string/times"
                                android:textColor="#8E8E93"
                                android:textSize="15sp" />

` : "";
  return `                    <androidx.appcompat.widget.LinearLayoutCompat
                        android:id="@+id/${o.id}"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:background="?android:attr/selectableItemBackground"
                        android:orientation="vertical"${vis}>

${divider}                        <androidx.appcompat.widget.LinearLayoutCompat
                            android:layout_width="match_parent"
                            android:layout_height="56dp"
                            android:gravity="center_vertical"
                            android:orientation="horizontal"
                            android:paddingStart="16dp"
                            android:paddingEnd="16dp">

                            <com.google.android.material.textview.MaterialTextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="@string/${o.title}"
                                android:textColor="?attr/colorOnSurface"
                                android:textSize="16sp" />

                            <com.google.android.material.textview.MaterialTextView
                                android:id="@+id/${o.value}"
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_marginStart="12dp"${end}
                                android:layout_weight="1"
                                android:ellipsize="${o.ellipsize || "end"}"
                                android:gravity="end"
                                android:singleLine="true"
                                android:textColor="#8E8E93"
                                android:textSize="15sp"${tools} />

${times}                            <androidx.appcompat.widget.AppCompatImageView
                                android:layout_width="20dp"
                                android:layout_height="20dp"
                                android:src="@drawable/ic_chevron_right" />

                        </androidx.appcompat.widget.LinearLayoutCompat>

                    </androidx.appcompat.widget.LinearLayoutCompat>`;
}
function card(margin, rows) {
  const top = margin ? `
                android:layout_marginTop="14dp"` : "";
  return `            <com.google.android.material.card.MaterialCardView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"${top}
                app:cardBackgroundColor="@color/white"
                app:cardCornerRadius="16dp"
                app:cardElevation="0dp"
                app:strokeWidth="0dp">

                <androidx.appcompat.widget.LinearLayoutCompat
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical">

${rows.join("\n\n")}

                </androidx.appcompat.widget.LinearLayoutCompat>

            </com.google.android.material.card.MaterialCardView>`;
}
const hidden = true;
const card1 = [
  ["playerButtons","player_button_config","playerButtonsText",false,false,"end","显示 17/19"],
  ["padLive","player_pad_live","padLiveText",true,false],
  ["exo4kCompat","player_exo_4k_compat","exo4kCompatText",true,false],
  ["kernel","player_kernel","kernelText",true,false],
  ["mpvConfig","player_mpv_config","mpvConfigText",true,false],
  ["blurayMenu","player_bluray_menu","blurayMenuText",true,false],
  ["scale","player_scale","scaleText",true,false],
  ["lut","player_lut","lutText",true,false],
  ["render","player_render","renderText",true,hidden,"end","Surface"],
  ["buffer","player_buffer","bufferText",true,hidden,"end","1",true],
  ["bufferBytes","player_buffer_bytes","bufferBytesText",true,hidden,"end","128MB"],
  ["backBuffer","player_back_buffer","backBufferText",true,hidden],
  ["playCache","player_cache","playCacheText",true,hidden],
  ["preload","player_preload","preloadText",true,hidden],
  ["preloadThread","player_preload_threads","preloadThreadText",true,hidden],
  ["preloadSize","player_preload_size","preloadSizeText",true,hidden],
  ["preloadTime","player_preload_time","preloadTimeText",true,hidden],
  ["preloadAhead","player_preload_ahead","preloadAheadText",true,hidden],
  ["preloadPause","player_preload_pause","preloadPauseText",true,hidden],
  ["tunnel","player_tunnel","tunnelText",true,hidden],
  ["audioDecode","player_audio_decode","audioDecodeText",true,hidden],
  ["audioPassThrough","player_audio_passthrough","audioPassThroughText",true,hidden],
  ["videoDecode","player_video_decode","videoDecodeText",true,hidden],
  ["aac","player_aac_track","aacText",true,hidden]
].map(([id,title,value,divider,gone,ellipsize,tools,times]) => row({id,title,value,divider,gone,ellipsize,tools,times}));
const card2 = [
  ["osd","player_osd","osdText",false,false,"end","标题、时间、进度"],
  ["caption","player_caption","captionText",true,false]
].map(([id,title,value,divider,gone,ellipsize,tools]) => row({id,title,value,divider,gone,ellipsize,tools}));
const card3 = [
  ["autoPlay","player_auto_play","autoPlayText",false,false],
  ["autoChange","player_auto_change","autoChangeText",true,false],
  ["speed","player_speed","speedText",true,false,"end","1",true],
  ["adblock","player_adblock","adblockText",true,false],
  ["background","player_background","backgroundText",true,false],
  ["ua","player_ua","uaText",true,false,"middle","okhttp/4.11.0"]
].map(([id,title,value,divider,gone,ellipsize,tools,times]) => row({id,title,value,divider,gone,ellipsize,tools,times}));
const xml = `<?xml version="1.0" encoding="utf-8"?>
<androidx.appcompat.widget.LinearLayoutCompat xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_setting"
    android:orientation="vertical">

    <com.google.android.material.appbar.MaterialToolbar
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="@color/bg_setting"
        app:navigationIconTint="?attr/colorOnSurface"
        app:title="@string/setting_player"
        app:titleTextAppearance="@style/ToolbarTextAppearance" />

    <androidx.core.widget.NestedScrollView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clipToPadding="false"
        android:fillViewport="true"
        android:overScrollMode="never">

        <androidx.appcompat.widget.LinearLayoutCompat
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:animateLayoutChanges="true"
            android:orientation="vertical"
            android:paddingStart="16dp"
            android:paddingTop="8dp"
            android:paddingEnd="16dp"
            android:paddingBottom="92dp">

${card(false, card1)}

${card(true, card2)}

${card(true, card3)}

        </androidx.appcompat.widget.LinearLayoutCompat>

    </androidx.core.widget.NestedScrollView>

</androidx.appcompat.widget.LinearLayoutCompat>
`;
const out = path.resolve("app/src/mobile/res/layout/fragment_setting_player.xml");
fs.writeFileSync(out, xml.replace(/\n/g, "\n"), "utf8");
const ids = [...xml.matchAll(/android:id="@\+id\/(\w+)"/g)].map(m => m[1]);
console.log(ids.join(","));
console.log("count", ids.length);
