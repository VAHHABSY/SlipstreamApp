# Use Ubuntu 22.04 as requested
FROM ubuntu:22.04

# Prevent interactive prompts during apt install
ENV DEBIAN_FRONTEND=noninteractive

# Install system dependencies
RUN apt-get update && apt-get install -y \
    wget \
    unzip \
    python3 \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /opt/android

# Download and install NDK r27d
RUN wget -q https://dl.google.com/android/repository/android-ndk-r27d-linux.zip \
    && unzip -q android-ndk-r27d-linux.zip \
    && rm android-ndk-r27d-linux.zip

# Add NDK to the system PATH
ENV PATH="/opt/android/android-ndk-r27d:${PATH}"

# Set the workspace for the source code
WORKDIR /workspace

# Default command
CMD ["ndk-build", "APP_ABI=arm64-v8a"]
