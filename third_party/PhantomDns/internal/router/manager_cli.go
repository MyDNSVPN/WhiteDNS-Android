package router

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// RunInteractiveManager منوی تعاملی مدیریت کاربران را در محیط ترمینال اجرا می‌کند
func RunInteractiveManager(configPath string) {
	reader := bufio.NewReader(os.Stdin)

	for {
		fmt.Println("\n=====================================")
		fmt.Println("🦇 PhantomDNS User Management 🦇")
		fmt.Println("=====================================")
		fmt.Println("1. Add/Update User")
		fmt.Println("2. List Users")
		fmt.Println("3. Exit")
		fmt.Print("Select an option (1-3): ")

		choiceStr, _ := reader.ReadString('\n')
		choice := strings.TrimSpace(choiceStr)

		switch choice {
		case "1":
			addUserInteractive(configPath, reader)
		case "2":
			listUsersInteractive(configPath)
		case "3":
			fmt.Println("Exiting manager...")
			return
		default:
			fmt.Println("❌ Invalid choice. Please try again.")
		}
	}
}

func addUserInteractive(configPath string, reader *bufio.Reader) {
	fmt.Print("\n🌐 Enter Domain (e.g., example.com): ")
	domain, _ := reader.ReadString('\n')
	domain = strings.TrimSpace(domain)

	fmt.Print("🔑 Enter Password: ")
	password, _ := reader.ReadString('\n')
	password = strings.TrimSpace(password)

	fmt.Print("🏷️  Enter Tag/Type (e.g., Public, Personal): ")
	tag, _ := reader.ReadString('\n')
	tag = strings.TrimSpace(tag)

	fmt.Print("📦 Enter MTU (Default 1400, press Enter to skip): ")
	mtuStr, _ := reader.ReadString('\n')
	mtuStr = strings.TrimSpace(mtuStr)

	mtu := 1400
	if mtuStr != "" {
		if parsed, err := strconv.Atoi(mtuStr); err == nil {
			mtu = parsed
		} else {
			fmt.Println("⚠️ Invalid MTU format, using default 1400.")
		}
	}

	config := loadRouterConfig(configPath)
	config.Routes[domain] = TunnelConfig{
		Password: password,
		MTU:      mtu,
		Tag:      tag,
	}

	saveRouterConfig(configPath, config)
}

func listUsersInteractive(configPath string) {
	config := loadRouterConfig(configPath)
	if len(config.Routes) == 0 {
		fmt.Println("\n📭 No users found.")
		return
	}

	fmt.Println("\n--- 📋 Current Users ---")
	count := 1
	for domain, route := range config.Routes {
		fmt.Printf("%d. Domain: %s | Tag: %s | MTU: %d\n", count, domain, route.Tag, route.MTU)
		count++
	}
}

func loadRouterConfig(path string) RouterConfig {
	var config RouterConfig
	config.Routes = make(map[string]TunnelConfig)

	data, err := os.ReadFile(path)
	if err == nil {
		_ = json.Unmarshal(data, &config)
	}

	if config.Routes == nil {
		config.Routes = make(map[string]TunnelConfig)
	}
	return config
}

func saveRouterConfig(path string, config RouterConfig) {
	dir := filepath.Dir(path)
	_ = os.MkdirAll(dir, 0755)

	data, err := json.MarshalIndent(config, "", "    ")
	if err != nil {
		fmt.Printf("❌ Error generating JSON: %v\n", err)
		return
	}

	if err := os.WriteFile(path, data, 0644); err != nil {
		fmt.Printf("❌ Error saving file: %v\n", err)
		return
	}
	fmt.Println("✅ users.json successfully updated! (Server will hot-reload automatically)")
}