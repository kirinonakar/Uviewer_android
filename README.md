# 📱 Uviewer (Integrated Viewer for Android)

[![Vibe Coding](https://img.shields.io/badge/Style-Vibe%20Coding-ff69b4?style=for-the-badge)](https://github.com/topics/vibe-coding)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-7F52FF?style=for-the-badge&logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-8.0+-3DDC84?style=for-the-badge&logo=android)](https://www.android.com)

**Uviewer** is an all-in-one media viewer for Android that lets you enjoy documents, images (manga), music, and videos in a single app. Beyond a simple viewer, it provides an optimized viewing experience through archive streaming and WebDAV remote server support.

> **Note**: This project was built with **Vibe Coding**. 🚀

---

## ✨ Key Features

### 📚 Document Viewer
- **Multiple Formats**: Support for `epub`, `aozora` (Aozora Bunko), `txt`, and `pdf`.
- **Smart Parsing**: Full support for Aozora Bunko format, including Ruby text, vertical writing, and emphasis dots (Bouten).
- **Auto Encoding Detection**: Automatically detects text encodings like `UTF-8`, `EUC-KR`, and `Shift-JIS` to prevent broken characters.
- **Custom Themes**: White, Sepia, Dark, and fully customizable background colors and font settings.

### 🖼️ Image & Manga Viewer
- **Archive Support**: Instantly View images inside `zip`, `cbz`, `rar`, and `7z` files without extraction.
- **View Modes**: Single page, Dual page (LTR/RTL), and Split view.
- **High-Quality Rendering**: Image sharpening filters and Zoom Lock functionality.
- **Remote Streaming**: Real-time browsing and loading of archives from WebDAV servers without full downloads.

### 🎬 Media Player
- **Integrated Playback**: Powerful video and music playback based on ExoPlayer.
- **Background Playback**: Service-based background playback support for music and audiobooks.
- **Gesture Controls**: Intuitive volume and seeking controls.

### 🌐 Network & Library
- **WebDAV Support**: Integration with private clouds (NAS, Nextcloud, etc.) for seamless file streaming.
- **Library Management**: Features for Recent Files, Favorites, and Pinning.
- **Data Security**: Secure storage of user settings and history using `SharedPreferences` and `Room` DB.

---

## 🛠 Tech Stack

Uviewer is built with the latest Android development technologies for a robust and fast experience.

- **UI Framework**: [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3)
- **Language**: [Kotlin](https://kotlinlang.org/)
- **Media Engine**: [AndroidX Media3 (ExoPlayer)](https://developer.android.com/guide/topics/media/media3)
- **Image Loading**: [Coil](https://coil-kt.github.io/coil/)
- **Database**: [Room Persistence Library](https://developer.android.com/training/data-storage/room)
- **Networking**: [OkHttp](https://square.github.io/okhttp/) & [Jsoup](https://jsoup.org/)
- **Archive Handling**: [Junrar](https://github.com/junrar/junrar), [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/)

---

## 🚀 Getting Started

### Prerequisites
- Android 8.0 (API Level 26) or higher

### Build & Run
1. Clone this repository.
2. Open the project in Android Studio (Ladybug or later recommended).
3. Sync Gradle and run the app.

---

## 🎨 Design Aesthetics

Uviewer follows these design principles to provide a premium user experience:
- **Fluid Animations**: Smooth screen transitions and interactions powered by Compose.
- **Full Dark Mode Support**: A sleek Dark Mode UI that syncs with system settings.
- **Responsive Layout**: Flexible layouts optimized for both phones and tablets.

---

## 🛡️ License

This project is distributed under the `MIT License`. See [LICENSE](LICENSE) for more information.

---

## 💬 A Final Word

> "Want to enjoy all your favorite content in one app without complex setups? **Uviewer** is the answer."
