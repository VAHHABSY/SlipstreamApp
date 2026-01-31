
# Slipstream App

Slipstream is a high-performance covert channel over DNS, powered by QUIC multipath. This Android application serves as a Graphical User Interface (GUI) and manager for the Slipstream ecosystem, establishing a secure, device-wide SOCKS5 proxy tunnel.

The app integrates the [SocksDroid](https://github.com/bndeff/socksdroid) VpnService implementation to capture device traffic and route it through the Slipstream tunnel without requiring system-wide proxy settings.

## Features

-   **DNS-over-QUIC Tunneling:** Leverages the `slipstream-client` for high-speed, covert data transmission.
    
-   **VpnService Integration:** Device-wide tunneling via a local VPN interface (no root required for the VPN logic).


## Building for Android

### Prerequisites
- Android Studio (latest version recommended)
- Android NDK r21e or compatible (r27d also tested and working)
- Android SDK with API level 34
- Gradle 8.x or higher

### Build Steps

#### Option 1: Using Android Studio
1. Clone the repository: `git clone https://github.com/VAHHABSY/SlipstreamApp.git`
2. Open the project in Android Studio
3. Let Gradle sync complete
4. Build the native libraries:
   - Open Terminal in Android Studio
   - Navigate to `app/src/main/jni/`
   - Run: `ndk-build` (ensure NDK is in your PATH)
5. Build APK: **Build → Build Bundle(s) / APK(s) → Build APK(s)**

#### Option 2: Using Command Line
1. Clone the repository:
   ```bash
   git clone https://github.com/VAHHABSY/SlipstreamApp.git
   cd SlipstreamApp
   ```
2. Build native libraries:
   ```bash
   cd app/src/main/jni
   ndk-build APP_ABI=arm64-v8a,armeabi-v7a,x86,x86_64
   cd ../../../..
   ```
3. Build APK:
   ```bash
   ./gradlew assembleDebug
   ```
4. Find APK at: `app/build/outputs/apk/debug/app-debug.apk`

#### Option 3: Using Docker (if Dockerfile is available)
If a Dockerfile is provided for building native libraries:
```bash
cd app/src/main
docker build -t slipstream-ndk .
docker run -v $(pwd)/../../../:/workspace slipstream-ndk
```

### Supported ABIs
- arm64-v8a (64-bit ARM)
- armeabi-v7a (32-bit ARM)
- x86 (32-bit Intel)
- x86_64 (64-bit Intel)

### Minimum Requirements
- Android 8.0 (API level 26) or higher
- Device with VPN permission capability

## Prerequisites

### Required
    
1.  **Binaries:** The following binaries must be present in the project's `assets` or `jni` folders:
    
    -   `slipstream-client`
        
    -   `proxy-client` (Go-based SSH executor)
        
    -   `pdnsd` and `tun2socks` (Compiled via NDK)
        
2.  **Network Configuration:** Remote server IP and the domain name configured for your Slipstream DNS service.

### Optional (For SSH Features)

-  **External Server (SSH Endpoint):** A server accessible via SSH acting as the remote end of the tunnel. Only required if you want to use SSH-based tunneling features.
    

## Setup Instructions

The connection can optionally use **SSH Public Key Authentication** to secure the tunnel between the `ssh-client` and your remote server.

**Note:** The SSH key is **optional**. The app can be started without a key file for testing or if you're using alternative authentication methods. However, full SSH tunnel functionality requires a valid key.

### Generate SSH Key Pair (Optional - for SSH features)

If you plan to use SSH features, generate a key pair on your machine (Recommended: Ed25519):

```
ssh-keygen -t ed25519 -f id_slipstream -N ""

```

### Server-Side Configuration (Optional - for SSH features)

Copy the contents of `id_slipstream.pub` and append it to the `/root/.ssh/authorized_keys` file on your **remote SSH server**.

### App Configuration

1.  **Key Placement (Optional):** If you want to use SSH features, place your private key (`id_slipstream`) in the application's internal storage directory and choose via file picker. If no key is provided, the app will still start but SSH-based features will not work.
    
2.  **Connection Details:** Enter the **Server IP** and **DNS Hostname** in the app's main interface.
    
3.  **Start Service:** Tap **Start**. The app will request permission to establish a VPN connection.
    
    - If no SSH key is provided, you'll see a warning but the app will continue to start
    - SSH tunnel features require a valid key file to function properly

## How it Works

1.  **Slipstream Layer:** The app executes `slipstream-client`, connecting to the remote DNS server to establish the QUIC carrier.
    
2.  **SSH Layer:** The [go-ssh-client](https://github.com/ChronoMonochrome/go-ssh-client) connects through the Slipstream pipe to the remote server, opening a SOCKS5 proxy listener on `127.0.0.1:3080`. **(Requires SSH key)**
    
3.  **VPN Layer:** The Android `VpnService` (based on SocksDroid) creates a virtual `tun0` interface.
    
4.  **Routing:**
    
    -   Traffic is captured by `tun0`.
        
    -   `tun2socks` converts IP packets to TCP streams.
        
    -   Streams are forwarded to the SOCKS5 proxy at port `3080`.
        
    -   `pdnsd` handles DNS resolution to prevent leaks.
        

## Credits & Resources

-   **Slipstream:** [EndPositive/slipstream](https://github.com/EndPositive/slipstream)
    
-   **VPN Logic:** Inspired by [bndeff/socksdroid](https://github.com/bndeff/socksdroid )
