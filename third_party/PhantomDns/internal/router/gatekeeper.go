// ==============================================================================
// PhantomDNS Core
// ==============================================================================

package router

import (
	"strings"
)

// ExtractDomain با سرعت بالا نام دامنه را از پکت خام DNS بیرون می‌کشد
func ExtractDomain(packet []byte) string {
	// هدر استاندارد DNS همیشه ۱۲ بایت است. اگر کمتر بود، پکت نامعتبر است.
	if len(packet) < 12 {
		return ""
	}

	offset := 12
	var labels []string

	// خواندن بخش‌های مختلف دامنه (Labels) تا زمانی که به بایت صفر برسیم
	for offset < len(packet) {
		length := int(packet[offset])
		if length == 0 {
			break // پایان نام دامنه
		}
		
		// هندل کردن فشرده‌سازی DNS (Pointers) که با 0xC0 شروع می‌شوند
		if length&0xC0 == 0xC0 {
			break
		}
		
		offset++
		// جلوگیری از خطای خروج از مرز آرایه (Panic) در صورت ارسال پکت‌های مخرب
		if offset+length > len(packet) {
			return "" 
		}
		
		labels = append(labels, string(packet[offset:offset+length]))
		offset += length
	}

	if len(labels) == 0 {
		return ""
	}

	// چسباندن بخش‌ها به هم و تبدیل به حروف کوچک برای یکسان‌سازی
	return strings.ToLower(strings.Join(labels, "."))
}