// ==============================================================================
// PhantomDNS Core
// ==============================================================================

package router

import (
	"encoding/binary"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// PendingQuery اطلاعات پکت اصلی کاربر را نگه می‌دارد تا جواب را گم نکنیم
type PendingQuery struct {
	ClientAddr   *net.UDPAddr
	OriginalTxID uint16
	Timestamp    time.Time
	Domain       string
}

// TxMuxer مدیریت آیدی‌های پکت و جلوگیری از تداخل کاربران را بر عهده دارد
type TxMuxer struct {
	pending  sync.Map
	nextTxID atomic.Uint32
}

// NewTxMuxer یک میکسر جدید می‌سازد و زباله‌روبِ حافظه را استارت می‌زند
func NewTxMuxer() *TxMuxer {
	mux := &TxMuxer{}
	// اجرای روتین پاکسازی پکت‌های تایم‌اوت شده در پس‌زمینه
	go mux.cleanupRoutine()
	return mux
}

// Allocate یک آیدی جدید سرور اختصاص می‌دهد و مشخصات کلاینت را ذخیره می‌کند
func (m *TxMuxer) Allocate(packet []byte, clientAddr *net.UDPAddr, domain string) (uint16, bool) {
	if len(packet) < 2 {
		return 0, false
	}

	// استخراج TXID اصلی کاربر (۲ بایت اول هدر DNS)
	originalTxID := binary.BigEndian.Uint16(packet[:2])
	
	pq := &PendingQuery{
		ClientAddr:   clientAddr,
		OriginalTxID: originalTxID,
		Timestamp:    time.Now(),
		Domain:       domain,
	}

	// جستجو برای پیدا کردن یک آیدی خالی و یکتا (نهایت ۶۵۵۳۶ تلاش)
	var serverTxID uint16
	var stored bool
	for i := 0; i < 65536; i++ {
		// ساخت یک آیدی جدید به صورت اتمیک (Thread-Safe)
		candidate := uint16(m.nextTxID.Add(1))
		
		// اگر این آیدی در مپ خالی بود، مشخصات کاربر را در آن ذخیره کن
		if _, loaded := m.pending.LoadOrStore(candidate, pq); !loaded {
			serverTxID = candidate
			stored = true
			break
		}
	}

	if !stored {
		return 0, false // حافظه آیدی‌ها پر شده است (Drop packet)
	}

	return serverTxID, true
}

// Restore بعد از پردازش در موتور ARQ، آیدی اصلی کاربر را برمی‌گرداند
func (m *TxMuxer) Restore(serverTxID uint16, responsePacket []byte) (*net.UDPAddr, bool) {
	// پیدا کردن و همزمان پاک کردن اطلاعات کاربر از مپ
	val, ok := m.pending.LoadAndDelete(serverTxID)
	if !ok {
		return nil, false // پکت استثناء یا تایم‌اوت شده است
	}

	pq := val.(*PendingQuery)

	// بازگرداندن آیدی اصلی کاربر به هدر جواب
	if len(responsePacket) >= 2 {
		binary.BigEndian.PutUint16(responsePacket[:2], pq.OriginalTxID)
	}

	return pq.ClientAddr, true
}

// cleanupRoutine پکت‌های معلق و رها شده را هر ۳۰ ثانیه پاک می‌کند تا سرور دچار نشتی حافظه (Memory Leak) نشود
func (m *TxMuxer) cleanupRoutine() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		now := time.Now()
		m.pending.Range(func(key, value any) bool {
			pq := value.(*PendingQuery)
			// اگر از زمان درخواست بیشتر از ۱۰ ثانیه گذشته بود، آن را پاک کن
			if now.Sub(pq.Timestamp) > 10*time.Second {
				m.pending.Delete(key)
			}
			return true
		})
	}
}