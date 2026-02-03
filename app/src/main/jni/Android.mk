# Copyright (C) 2009 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
#
LOCAL_PATH := $(call my-dir)
ROOT_PATH := $(LOCAL_PATH)

########################################################
## pdnsd library
########################################################

include $(CLEAR_VARS)

PDNSD_SOURCES  := $(wildcard $(LOCAL_PATH)/pdnsd/src/*.c)

LOCAL_MODULE    := pdnsd
LOCAL_SRC_FILES := $(PDNSD_SOURCES:$(LOCAL_PATH)/%=%)
LOCAL_CFLAGS    := -Wall -O2 -I$(LOCAL_PATH)/pdnsd -DHAVE_STPCPY

include $(BUILD_EXECUTABLE)

########################################################
## libancillary
########################################################

include $(CLEAR_VARS)

ANCILLARY_SOURCE := fd_recv.c fd_send.c

LOCAL_MODULE := libancillary
LOCAL_CFLAGS := -O2 -I$(LOCAL_PATH)/libancillary

LOCAL_SRC_FILES := $(addprefix libancillary/, $(ANCILLARY_SOURCE))

include $(BUILD_STATIC_LIBRARY)

########################################################
## system
########################################################

include $(CLEAR_VARS)

LOCAL_MODULE:= system

LOCAL_C_INCLUDES:= $(LOCAL_PATH)/libancillary

LOCAL_SRC_FILES:= system.cpp

LOCAL_LDLIBS := -ldl -llog

LOCAL_STATIC_LIBRARIES := cpufeatures libancillary

include $(BUILD_SHARED_LIBRARY)

########################################################
## tun2socks
########################################################

include $(CLEAR_VARS)

LOCAL_MODULE := tun2socks  # <-- Added this line

LOCAL_CFLAGS := -std=gnu99
LOCAL_CFLAGS += -DBADVPN_THREADWORK_USE_PTHREAD -DBADVPN_LINUX -DBADVPN_BREACTOR_BADVPN -D_GNU_SOURCE
LOCAL_CFLAGS += -DBADVPN_USE_SELFPIPE -DBADVPN_USE_EPOLL
LOCAL_CFLAGS += -DBADVPN_LITTLE_ENDIAN -DBADVPN_THREAD_SAFE
LOCAL_CFLAGS += -DNDEBUG -DANDROID
# LOCAL_CFLAGS += -DTUN2SOCKS_JNI

LOCAL_STATIC_LIBRARIES := libancillary

LOCAL_C_INCLUDES:= \
		$(LOCAL_PATH)/libancillary \
        $(LOCAL_PATH)/badvpn/lwip/src/include/ipv4 \
        $(LOCAL_PATH)/badvpn/lwip/src/include/ipv6 \
        $(LOCAL_PATH)/badvpn/lwip/src/include \
        $(LOCAL_PATH)/badvpn/lwip/custom \
        $(LOCAL_PATH)/badvpn/

TUN2SOCKS_SOURCES := \
        base/BLog_syslog.c \
        system/BReactor_badvpn.c \
        system/BSignal.c \
        system/BConnection_unix.c \
        system/BTime.c \
        system/BUnixSignal.c \
        system/BNetwork.c \
        flow/StreamRecvInterface.c \
        flow/PacketRecvInterface.c \
        flow/PacketPassInterface.c \
        flow/StreamPassInterface.c \
        flow/PacketPassFairQueue.c \
        flow/PacketPassConnector.c \
        flow/PacketProtoDecoder.c \
        flow/PacketProtoEncoder.c \
        flow/PacketProtoFlow.c \
        flow/SinglePacketBuffer.c \
        flow/BufferWriter.c \
        flow/PacketBuffer.c \
        flow/PacketPassNotifier.c \
        flow/PacketRecvConnector.c \
        flow/LineBuffer.c \
        flow/BPacket.c \
        system/BProcess.c \
        system/BInputProcess.c \
        system/BDatagram.c \
        system/BConnection.c \
        system/BAddr.c \
        system/BDatagram_unix.c \
        system/BInputChain.c \
        system/BOutputChain.c \
        system/BLockReactor.c \
        system/BThreadWork.c \
        system/BPending.c \
        system/BThreadSignal.c \
        system/BSelect.c \
        system/BTap.c \
        system/BUnixDatagram.c \
        system/BFileDescriptor.c \
        flowextra/PacketPassInactivityMonitor.c \
        flowextra/StreamPacketSender.c \
        flowextra/DatagramSocket.c \
        flowextra/SocksUdpGwClient.c \
        flowextra/DHCPClient.c \
        flowextra/DHCPClientCore.c \
        flowextra/PasswordListener.c \
        flowextra/Listener.c \
        flowextra/BProcessSignal.c \
        flowextra/SignalSource.c \
        flowextra/StreamSocketSource.c \
        flowextra/LineReader.c \
        flowextra/BSocks_Incoming.c \
        flowextra/BSocks_Outgoing.c \
        flowextra/BSocks.c \
        flowextra/SocksClient.c \
        flowextra/HttpProxy.c \
        tun2socks/tun2socks.c \
        base/BLog.c \
        base/DebugObject.c \
        base/BPendingGroup.c \
        base/BInteger.c \
        udevmonitor/NCDUdevManager.c \
        random/BRandom2.c \
        ncd/NCDVal.c \
        ncd/NCDStringIndex.c \
        ncd/NCDModuleIndex.c \
        lwip/src/core/init.c \
        lwip/src/core/def.c \
        lwip/src/core/dns.c \
        lwip/src/core/inet_chksum.c \
        lwip/src/core/ip.c \
        lwip/src/core/mem.c \
        lwip/src/core/memp.c \
        lwip/src/core/netif.c \
        lwip/src/core/pbuf.c \
        lwip/src/core/raw.c \
        lwip/src/core/stats.c \
        lwip/src/core/sys.c \
        lwip/src/core/tcp.c \
        lwip/src/core/tcp_in.c \
        lwip/src/core/tcp_out.c \
        lwip/src/core/timers.c \
        lwip/src/core/udp.c \
        lwip/src/core/ipv4/autoip.c \
        lwip/src/core/ipv4/icmp.c \
        lwip/src/core/ipv4/igmp.c \
        lwip/src/core/ipv4/inet.c \
        lwip/src/core/ipv4/inet_chksum.c \
        lwip/src/core/ipv4/ip.c \
        lwip/src/core/ipv4/ip_addr.c \
        lwip/src/core/ipv4/ip_frag.c \
        lwip/src/core/ipv4/dhcp.c \
        lwip/src/core/ipv4/etharp.c \
        lwip/src/core/ipv6/icmp6.c \
        lwip/src/core/ipv6/inet6.c \
        lwip/src/core/ipv6/ip6.c \
        lwip/src/core/ipv6/ip6_addr.c \
        lwip/src/core/ipv6/ip6_frag.c \
        lwip/src/core/ipv6/mld6.c \
        lwip/src/core/ipv6/nd6.c \
        lwip/src/core/ipv6/ethip6.c \
        lwip/custom/sys.c \
        tun2socks/SocksUdpGwClient.c

LOCAL_SRC_FILES := $(addprefix badvpn/, $(TUN2SOCKS_SOURCES))

LOCAL_LDLIBS := -ldl -llog

include $(BUILD_SHARED_LIBRARY)