package router

import (
	"encoding/json"
	"log"
	"os"
	"sync"
	"time"

	"phantomdns-go/internal/security"
)

type TunnelConfig struct {
	Password string `json:"password"`
	MethodID int    `json:"method_id"`
	MTU      int    `json:"mtu"`
	Tag      string `json:"tag"`
	codec    *security.Codec
}

type RouterConfig struct {
	Routes map[string]TunnelConfig `json:"routes"`
}

type ConfigManager struct {
	mu           sync.RWMutex
	routes       map[string]TunnelConfig
	configPath   string
	lastModified time.Time
}

func NewConfigManager(path string) *ConfigManager {
	cm := &ConfigManager{
		routes:     make(map[string]TunnelConfig),
		configPath: path,
	}

	cm.loadConfig()
	go cm.watchConfig()

	return cm
}

func (cm *ConfigManager) loadConfig() {
	fileInfo, err := os.Stat(cm.configPath)
	if err != nil {
		log.Printf("⚠️ اخطار: فایل کانفیگ پیدا نشد: %v", err)
		return
	}

	if !fileInfo.ModTime().After(cm.lastModified) {
		return
	}

	data, err := os.ReadFile(cm.configPath)
	if err != nil {
		log.Printf("❌ خطا در خواندن فایل: %v", err)
		return
	}

	var newConfig RouterConfig
	if err := json.Unmarshal(data, &newConfig); err != nil {
		log.Printf("❌ خطا در پارس کردن جیسون: %v", err)
		return
	}

	for domain, route := range newConfig.Routes {
		codec, err := security.NewCodec(2, route.Password)
		if err != nil {
			log.Printf("❌ خطا در ساخت کلید برای کاربر %s: %v", domain, err)
			continue
		}
		route.codec = codec
		newConfig.Routes[domain] = route
	}

	cm.mu.Lock()
	cm.routes = newConfig.Routes
	cm.lastModified = fileInfo.ModTime()
	cm.mu.Unlock()

	log.Println("✅ [Hot-Reload] جدول کاربران و کلیدهای رمزنگاری با موفقیت آپدیت شد!")
}

func (cm *ConfigManager) watchConfig() {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		cm.loadConfig()
	}
}

func (cm *ConfigManager) GetRoute(domain string) (TunnelConfig, bool) {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	route, exists := cm.routes[domain]
	return route, exists
}

func (cm *ConfigManager) GetCodec(domain string) *security.Codec {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	route, exists := cm.routes[domain]
	if !exists {
		return nil
	}
	return route.codec
}