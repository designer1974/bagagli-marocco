package main

import (
	"context"
	"log/slog"
)

// Notifier manda al destinatario il codice di ritiro.
// Oggi scrive solo nel log; si sostituisce con un fornitore SMS/WhatsApp reale.
type Notifier interface {
	SendRecipientCode(ctx context.Context, phone, name, code string) error
}

type logNotifier struct{}

func (logNotifier) SendRecipientCode(_ context.Context, phone, name, _ string) error {
	// Il codice non va mai scritto nei log.
	slog.Info("SMS codice ritiro (simulato)", "to", maskPhone(phone), "name", name)
	return nil
}

func maskPhone(p string) string {
	if len(p) <= 4 {
		return "****"
	}
	return "****" + p[len(p)-4:]
}
