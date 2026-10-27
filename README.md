# Kara-OK for Android TV

Offline videoke for Android TV boxes, Google TV, phones and tablets (Android 6 and newer).
For personal use.

## What it does

- **MIDI karaoke songs** (.mid, .kar) with word-by-word lyrics, played with a SoundFont (one is built in).
- **MP3, MP4 and other audio/video**, and **CD+G** (.mp3 + .cdg, or an MP3+G .zip).
- **Key change** (up to 6 steps either way) and **tempo** (80–120%) on every song, instantly.
- **Guide melody** on/off for MIDI songs, **vocal cut** for normal recordings.
- **Song numbers**: press the number keys on the TV remote to reserve.
- **Reserve first**, a singer queue, and a 3-2-1 "Now singing" screen.
- **Phone remote**: scan the QR code on the TV to search, reserve, change key, play sound effects,
  and **upload songs from your phone or computer**. No app needed on the phone.
- **Mic** (USB mic) with echo, delay and reverb, plus presets.
- **Sound effects**: applause, airhorn, drum roll, fanfare, ba-dum-tss (colour keys on the remote play the first four).
- **Scores**: Classic, Stars or Party (Tagalog).
- **Videoke 2-line lyrics** or scrolling 4-line lyrics.
- **Languages**: songs are sorted into OPM/Tagalog, English, Korean, Japanese, Chinese and Thai,
  with a filter and a "hide duplicates" option.
- **Background videos** for MIDI songs: put videos in a `Backgrounds` folder inside `Kara-OK`.

## Getting the APK

Every time this repository changes, GitHub builds the app automatically (Actions tab).
The newest APK is published under **Releases → latest → Kara-OK.apk**.

## Installing on a TV box

1. On the TV box: Settings → Security (or Device preferences → Security & restrictions) →
   allow **Unknown sources** for the app you'll install with (for example "Downloader" or a file manager).
2. Get `Kara-OK.apk` onto the box, either of these ways:
   - Install the free **Downloader** app from the TV's app store and type the APK's download link, or
   - copy the APK to a USB stick and open it with the box's file manager.
3. Open Kara-OK. Allow file access if you want it to find songs on the box or a USB drive.

## Adding songs

- **From your phone:** scan the QR code on the TV → **Upload** tab → choose files.
- **From storage / USB:** put songs in a folder named `Kara-OK` (or `Karaoke`, `Music`, `Download`, `SongHub`),
  then Settings → *Look for new songs now*.

## Remote control

| Key | Does |
| --- | --- |
| OK / arrows | show the control bar |
| Number keys | reserve a song by number |
| Play/Pause | play / pause |
| Next / Fast-forward | next singer |
| Channel up / down | key up / down |
| Red, Green, Yellow, Blue | sound effects |
| Back | close a panel / exit |

## Building it yourself (optional)

Open this folder in Android Studio and press Run, or run `./gradlew assembleRelease`.
