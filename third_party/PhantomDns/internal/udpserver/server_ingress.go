// ==============================================================================
// phantomdns
// Author: DrAlanK
// Github: https://github.com/DrAlanK
// Year: 2026
// ==============================================================================

package udpserver

import (
	"errors"
	"fmt"
	"strings"
	"time"

	DnsParser "phantomdns-go/internal/dnsparser"
	domainMatcher "phantomdns-go/internal/domainmatcher"
	Enums "phantomdns-go/internal/enums"
	VpnProto "phantomdns-go/internal/vpnproto"
)

func (s *Server) handlePacket(packet []byte) []byte {
	parsed, err := DnsParser.ParseDNSRequestLite(packet)
	if err != nil {
		if errors.Is(err, DnsParser.ErrNotDNSRequest) || errors.Is(err, DnsParser.ErrPacketTooShort) {
			return nil
		}

		return s.buildNoDataResponseLogged(packet, "request-parse-failed")
	}

	if !parsed.HasQuestion {
		return s.buildNoDataResponseLogged(packet, "request-has-no-question")
	}

	decision := s.domainMatcher.Match(parsed)
	switch decision.Action {
	case domainMatcher.ActionProcess:
		response := s.handleTunnelCandidate(packet, parsed, decision)
		if response != nil {
			return response
		}

		return s.buildNoDataResponseLiteLogged(packet, parsed, "domain-match-process-failed")
	case domainMatcher.ActionFormatError:
		return s.buildFormatErrorResponseLiteLogged(packet, parsed, decision.Reason)
	case domainMatcher.ActionNoData:
		if decision.Reason == "unauthorized-domain" {
			return s.buildNameErrorResponseLiteLogged(packet, parsed, decision.Reason)
		}
		return s.buildNoDataResponseLiteLogged(packet, parsed, decision.Reason)
	default:
		return s.buildNoDataResponseLiteLogged(packet, parsed, "domain-match-unknown-action")
	}
}

func (s *Server) handleTunnelCandidate(packet []byte, parsed DnsParser.LitePacket, decision domainMatcher.Decision) []byte {
	// ==============================================================================
	// 🚀 DYNAMIC CODEC INJECTION (PhantomDNS Gatekeeper)
	// ==============================================================================
	
	// ۱. استخراج دامنه پایه برای جستجو در users.json
	var baseDomain string
	for _, d := range s.cfg.Domain {
		if decision.RequestName == d || strings.HasSuffix(decision.RequestName, "."+d) {
			baseDomain = d
			break
		}
	}

	// ۲. دریافت کلید رمزنگاری اختصاصی کاربر
	userCodec := s.configManager.GetCodec(baseDomain)
	if userCodec == nil {
		if s.log != nil {
			s.log.Debugf("❌ Drop: No user config found for base domain: %s", baseDomain)
		}
		return s.buildNoDataResponseLiteLogged(packet, parsed, "unauthorized-user-tunnel")
	}

	// ۳. بازگشایی پکت با کلید اختصاصی
	vpnPacket, err := VpnProto.ParseInflatedFromLabels(decision.Labels, userCodec)
	// ==============================================================================

	if err != nil {
		return s.buildNoDataResponseLiteLogged(packet, parsed, "vpn-proto-parse-failed")
	}

	if vpnPacket.PacketType == Enums.PACKET_SESSION_CLOSE {
		s.handleSessionCloseNotice(vpnPacket, time.Now())
		return s.buildNoDataResponseLiteLogged(packet, parsed, "session-close-notice")
	}

	if !isPreSessionRequestType(vpnPacket.PacketType) {
		validation := s.validatePostSessionPacket(packet, decision.RequestName, vpnPacket)
		if !validation.ok {
			return validation.response
		}

		if !s.handlePostSessionPacket(vpnPacket, validation.record) {
			return s.buildNoDataResponseLiteLogged(packet, parsed, fmt.Sprintf("post-session-unhandled-%s", Enums.PacketTypeName(vpnPacket.PacketType)))
		}

		return s.serveQueuedOrPong(packet, decision.RequestName, validation.record, time.Now())
	}

	switch vpnPacket.PacketType {
	case Enums.PACKET_MTU_UP_REQ:
		return s.handleMTUUpRequest(packet, parsed, decision, vpnPacket)
	case Enums.PACKET_MTU_DOWN_REQ:
		return s.handleMTUDownRequest(packet, parsed, decision, vpnPacket)
	case Enums.PACKET_SESSION_INIT:
		return s.handleSessionInitRequest(packet, decision, vpnPacket)
	default:
		return s.buildNoDataResponseLiteLogged(packet, parsed, fmt.Sprintf("pre-session-unhandled-%s", Enums.PacketTypeName(vpnPacket.PacketType)))
	}
}