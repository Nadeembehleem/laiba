# LAIBA AI 3D — V30.1 (realistic avatar + phone control + AI brain)

## Realistic avatar (new)
The default avatar is now **your reference girl**, rendered from the concept image as a depth-displaced 2.5D mesh (`portrait.js`):
parallax look-around (touch drag / gyro), breathing, random blinking, lip movement while speaking, wave (hand), dance sway,
RGB neon lighting, and Full/Face zoom. It is a 2.5D photo-based avatar, not a full-body rigged mesh: she cannot turn
fully sideways. For a true rigged model use the cube button -> "Import my own 3D model (.glb)".
Assets: `app/src/main/assets/avatar/laiba_portrait.jpg` (cleaned plate) and `laiba_depth.png`. Replace them (same pixel layout) to change the face.

## Phone control + AI
- Direct actions: open apps, call, SMS, WhatsApp, alarms/timers, flashlight, volume, brightness, media keys, maps, web search, settings pages.
- Via Accessibility: read screen, tap/type/scroll/swipe, back/home/recents/notifications/quick settings/screenshot/lock.
- Calls, SMS and Send/Pay/Buy/Delete-type taps always ask Allow/Deny.
- Brain: Gemini (+Google Search), DeepSeek, OpenAI or compatible. Memory, Hinglish, code mode, screen vision, image analysis.

## Setup
1. Push to GitHub -> Actions -> "Build LAIBA Rebuilt APK" -> install the debug APK.
2. Gear -> provider + API key -> Save. Gear -> Enable phone control (Accessibility) + Grant permissions.
3. Try: "WhatsApp kholo", "5 minute ka timer lagao", "flashlight on karo", "Mummy ko call karo".

Not compiled on a device by the author (no Android SDK available). Send the Actions log if the build fails.

## Voice (V30.2)
Gear -> Voice: pick any installed phone voice, adjust pitch/speed, or enable "Natural voice with Gemini speech" (Gemini provider only;
voices Kore/Aoede/Leda/Zephyr/Puck/Charon). If Gemini speech fails, LAIBA falls back to the phone voice automatically.
