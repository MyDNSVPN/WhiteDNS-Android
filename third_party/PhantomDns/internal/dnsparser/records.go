package dnsparser

import (
	"bytes"
	"encoding/base32"
	"encoding/binary"
	"fmt"
	"io"
	"strings"
)

var b32Encoding = base32.StdEncoding.WithPadding(base32.NoPadding)

// ==============================================================================
// 1. IP-Based Steganography (A & AAAA Records)
// ==============================================================================

func encodeRDataMultiRR(p []byte, chunkSize int) [][]byte {
	buf := make([]byte, 2+len(p))
	binary.BigEndian.PutUint16(buf, uint16(len(p)))
	copy(buf[2:], p)
	var chunks [][]byte
	for len(buf) > 0 {
		chunkSizeActual := chunkSize
		if len(buf) < chunkSize {
			chunkSizeActual = len(buf)
		}
		chunk := make([]byte, chunkSize) // zero-padded automatically
		copy(chunk, buf[:chunkSizeActual])
		buf = buf[chunkSizeActual:]
		chunks = append(chunks, chunk)
	}
	return chunks
}

func decodeRDataMultiRR(chunks [][]byte) ([]byte, error) {
	var buf bytes.Buffer
	for _, chunk := range chunks {
		buf.Write(chunk)
	}
	data := buf.Bytes()
	if len(data) < 2 {
		return nil, io.ErrUnexpectedEOF
	}
	n := int(binary.BigEndian.Uint16(data))
	data = data[2:]
	if len(data) < n {
		return nil, io.ErrUnexpectedEOF
	}
	return data[:n], nil
}

// EncodeRDataA دیتا را به مجموعه‌ای از آی‌پی‌های 4 بایتی فیک تبدیل می‌کند
func EncodeRDataA(p []byte) [][]byte { return encodeRDataMultiRR(p, 4) }

// DecodeRDataA دیتای مخفی شده در آی‌پی‌های IPv4 را بازسازی می‌کند
func DecodeRDataA(chunks [][]byte) ([]byte, error) { return decodeRDataMultiRR(chunks) }

// EncodeRDataAAAA دیتا را به مجموعه‌ای از آی‌پی‌های 16 بایتی فیک تبدیل می‌کند
func EncodeRDataAAAA(p []byte) [][]byte { return encodeRDataMultiRR(p, 16) }

// DecodeRDataAAAA دیتای مخفی شده در آی‌پی‌های IPv6 را بازسازی می‌کند
func DecodeRDataAAAA(chunks [][]byte) ([]byte, error) { return decodeRDataMultiRR(chunks) }

// ==============================================================================
// 2. Domain-Based Steganography (CNAME, MX, SRV Records)
// ==============================================================================

func encodePayloadAsName(p []byte, baseDomain string) (string, error) {
	const labelLen = 63

	encoded := make([]byte, b32Encoding.EncodedLen(len(p)))
	b32Encoding.Encode(encoded, p)
	encoded = bytes.ToLower(encoded)

	var labels []string
	for len(encoded) > labelLen {
		labels = append(labels, string(encoded[:labelLen]))
		encoded = encoded[labelLen:]
	}
	if len(encoded) > 0 {
		labels = append(labels, string(encoded))
	}
	

	fullDomain := fmt.Sprintf("%s.%s", bytes.Join(bytes.Split([]byte(strings.Join(labels, ".")), []byte("")), []byte("")), baseDomain)
	return fullDomain, nil
}

func EncodeRDataCNAME(p []byte, baseDomain string) (string, error) {
	return encodePayloadAsName(p, baseDomain)
}

func EncodeRDataSRV(p []byte, baseDomain string) ([]byte, error) {
	nameStr, err := encodePayloadAsName(p, baseDomain)
	if err != nil {
		return nil, err
	}
	rdata := make([]byte, 6+len(nameStr)+1) 
	copy(rdata[6:], []byte(nameStr))
	return rdata, nil
}

func decodePayloadFromName(fullDomain string, baseDomain string) ([]byte, error) {
	baseDomain = strings.ToLower(strings.TrimSuffix(baseDomain, "."))
	fullDomain = strings.ToLower(strings.TrimSuffix(fullDomain, "."))
	
	if !strings.HasSuffix(fullDomain, baseDomain) {
		return nil, fmt.Errorf("domain mismatch")
	}
	
	// حذف دامنه پایه و نقطه‌های اضافی
	encodedStr := strings.TrimSuffix(fullDomain, "."+baseDomain)
	encodedStr = strings.ReplaceAll(encodedStr, ".", "")
	encodedStr = strings.ToUpper(encodedStr)
	
	payload := make([]byte, b32Encoding.DecodedLen(len(encodedStr)))
	n, err := b32Encoding.Decode(payload, []byte(encodedStr))
	if err != nil {
		return nil, err
	}
	return payload[:n], nil
}

// DecodeRDataCNAME دیتای مخفی شده در CNAME را استخراج می‌کند
func DecodeRDataCNAME(rdata []byte, baseDomain string) ([]byte, error) {
	name, _, err := parseName(rdata, 0)
	if err != nil {
		return nil, err
	}
	return decodePayloadFromName(name, baseDomain)
}

// DecodeRDataSRV دیتای مخفی شده در SRV (بعد از ۶ بایت اول) را استخراج می‌کند
func DecodeRDataSRV(rdata []byte, baseDomain string) ([]byte, error) {
	if len(rdata) < 6 {
		return nil, io.ErrUnexpectedEOF
	}
	name, _, err := parseName(rdata, 6)
	if err != nil {
		return nil, err
	}
	return decodePayloadFromName(name, baseDomain)
}