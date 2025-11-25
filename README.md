# Slipstream App

Slipstream is a high-performance covert channel over DNS, powered by QUIC multipath. This Android application serves as a Graphical User Interface (GUI) and executor for the slipstream-client binary, establishing a secure SOCKS5 proxy tunnel on the device using the binary and the device's native ssh tool. The application requires root access (su) to execute the necessary commands and must be configured with key-based SSH authentication.

## Prerequisites

1.  **Rooted Android Device:** The application **requires superuser (root) privileges** to execute `su -c "ssh..."`.

2.  **External Server (SSH Endpoint):** A server accessible via SSH that will act as the far end of the tunnel.

3.  **Slipstream Client Binary**: The `slipstream-client` binary (from the [Slipstream repository](https://github.com/EndPositive/slipstream)) must be included in the Android project's `assets` folder.

4.  **Network Configuration:** You must know the remote server's IP address and the domain name configured for the slipstream service.

## Setup Instructions

The SSH connection established by this application is configured to use **key-based authentication** (Public Key Authentication) for the `root@localhost` user. This means you must set up the keys on the remote server *before* running the service.

### 1. Generate SSH Key Pair (Recommended: Ed25519)

On your remote server (or local machine, if you need to transfer the key later), generate an Ed25519 key pair. This is the modern, recommended standard.
Generate the key pair, leaving the passphrase blank for automatic login

NOTE: Using 'ed25519' is preferred for security and performance.

ssh-keygen -t ed25519 -f ~/.ssh/id_slipstream -N ""
* This creates two files: `~/.ssh/id_slipstream` (the private key) and `~/.ssh/id_slipstream.pub` (the public key).

### 2. Copy the Public Key to the Authorized Keys

The public key (`id_slipstream.pub`) must be copied to the authorized keys file (`~/.ssh/authorized_keys`) on the **remote SSH server**.

Use `ssh-copy-id` if available, or manually append the key:

#### Option A: Using `ssh-copy-id` (Recommended)
ssh-copy-id -i ~/.ssh/id_slipstream.pub root@remote_server
#### Option B: Manual Copy

Copy the contents of `~/.ssh/id_slipstream.pub` and manually paste them into the `~/.ssh/authorized_keys` file on the remote server.

### 3. Place the Private Key on the Android Device

The application relies on the Android environment to have the SSH private key accessible to the native `ssh` client.

**This is the crucial step for this application:** Since the application runs `ssh` via `su` as the `root` user, the `ssh` command will look for the private key in the root user's SSH directory on the Android device, which is typically `/data/ssh/`.

* The generated **private key** (`id_slipstream`) must be transferred to the Android device and placed in the location where the `su -c ssh` command can find it (e.g., `/data/ssh/id_rsa` or similar path accessible by the root user).

* **The current application code is configured to run `ssh` without passing a specific private key path, relying on the operating system's default key resolution (i.e., it expects the key to be found automatically via agent or default paths).** Ensure your key management setup on the rooted device allows the `root` user to access the necessary key for `localhost` without requiring a password.

### 4. Configure and Run the Android Service

1.  In the app's main screen, enter the **IP Address** and **Domain Name** for the slipstream connection. These values are automatically saved using `SharedPreferences` upon starting the service.

2.  Tap the **Start** button.

3.  The application will request **Notification Permission** (if on Android 13+) to run as a Foreground Service.

4.  The service will:
    a.  Execute the `slipstream-client` binary.
    b.  Wait for the "Connection confirmed." message.
    c.  If confirmed, it executes `su -c "ssh -p 5201 -ND 3080 root@localhost"` to establish the SOCKS5 proxy tunnel.

### 5. Stopping the Service

To stop the tunnel and the background processes, simply tap the **Stop** button. The application is designed to send graceful termination signals (`SIGTERM`) followed by a forced kill (`SIGKILL`) to both the `ssh/su` process and the `slipstream-client` process to ensure a clean shutdown and prevent the server from being left in a hung state.

## Resources

* Slipstream Repository (Source): https://github.com/EndPositive/slipstream
