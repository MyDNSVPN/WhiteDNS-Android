// ==============================================================================
// phantomdns
// Author: DrAlanK
// Github: https://github.com/DrAlanK
// Year: 2026
// ==============================================================================

package vpnproto

import (
	"errors"
	"strings"

	"phantomdns-go/internal/compression"
	"phantomdns-go/internal/security"
)

var ErrInvalidCompressedPayload = errors.New("invalid compressed vpn payload")

// ==============================================================================
// 🚀 GHOST MODE (Entropy Evasion Sanitizer)
// ==============================================================================
// دیکشنری کلمات فیک؛ هر کلمه‌ای که کلاینت برای گمراه کردن فایروال اضافه کند
// باید در این لیست باشد تا سرور آن را نادیده بگیرد.
var noiseWords = map[string]bool{
	"api": true, "cdn": true, "www": true, "update": true,
	"telemetry": true, "v1": true, "v2": true, "static": true,
	"app": true, "web": true, "dev": true, "gw": true,
	"upload": true, "download": true, "auth": true, "metric": true,
	"dns": true, "cloudflare": true, "google": true,
}

// تابع پاک‌سازی: رشته‌های دریافتی را می‌شکند، نویزها را حذف می‌کند و دیتای خالص را برمی‌گرداند
func sanitizeLabels(labels string) string {
	parts := strings.Split(labels, ".")
	var cleanParts []string
	
	for _, part := range parts {
		if noiseWords[strings.ToLower(part)] {
			continue
		}
		cleanParts = append(cleanParts, part)
	}
	
	return strings.Join(cleanParts, ".")
}
// ==============================================================================

func PreparePayload(packetType uint8, payload []byte, requestedCompression uint8, minSize int) ([]byte, uint8) {
	requestedCompression = compression.NormalizeAvailableType(requestedCompression)
	if requestedCompression == compression.TypeOff {
		return payload, compression.TypeOff
	}

	if !hasCompressionExtension(packetType) {
		return payload, compression.TypeOff
	}
	if len(payload) == 0 {
		return payload, compression.TypeOff
	}
	return compression.CompressPayload(payload, requestedCompression, minSize)
}

func InflatePayload(packet Packet) (Packet, error) {
	if !packet.HasCompressionType || packet.CompressionType == compression.TypeOff {
		return packet, nil
	}

	payload, ok := compression.TryDecompressPayload(packet.Payload, packet.CompressionType)
	if !ok {
		return Packet{}, ErrInvalidCompressedPayload
	}
	packet.Payload = payload
	return packet, nil
}

func ParseInflatedFromLabels(labels string, codec *security.Codec) (Packet, error) {
	// 🚀 عبور دادن لیبل‌ها از فیلتر نویزگیر قبل از رمزگشایی
	cleanLabels := sanitizeLabels(labels)

	packet, err := ParseFromLabels(cleanLabels, codec)
	if err != nil {
		return Packet{}, err
	}

	return InflatePayload(packet)
}

func ParseInflated(data []byte) (Packet, error) {
	packet, err := Parse(data)
	if err != nil {
		return Packet{}, err
	}

	return InflatePayload(packet)
}

func BuildRawAuto(opts BuildOptions, minSize int) ([]byte, error) {
	payload, compressionType := PreparePayload(opts.PacketType, opts.Payload, opts.CompressionType, minSize)
	opts.Payload = payload
	opts.CompressionType = compressionType
	return BuildRaw(opts)
}

func BuildEncodedAuto(opts BuildOptions, codec *security.Codec, minSize int) (string, error) {
	raw, err := BuildRawAuto(opts, minSize)
	if err != nil {
		return "", err
	}
	if codec == nil {
		return "", ErrCodecUnavailable
	}
	return codec.EncryptAndEncode(raw)
}