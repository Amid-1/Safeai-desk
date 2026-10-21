
import type { ChatTrustItem } from './chatTrustInfo'
import { CHAT_TRUST_ITEMS } from './chatTrustInfo'

type ChatHeroProps = {
    onSelectTrustItem: (item: ChatTrustItem) => void
}

export default function ChatHero({ onSelectTrustItem }: ChatHeroProps) {
    return (
        <header className="chat-hero">
            <div>
                <span className="chat-hero__eyebrow">
                    Защищённое рабочее пространство
                </span>
                <h1>Корпоративный AI</h1>
                <p>
                    Общайтесь с AI, подключайте разрешённые базы знаний
                    и проверяйте источники каждого ответа.
                </p>
            </div>
            <div className="chat-hero__trust" aria-label="Как SafeAI защищает работу с AI">
                {CHAT_TRUST_ITEMS.map((item) => (
                    <button
                        key={item.id}
                        type="button"
                        className="chat-trust-button"
                        onClick={() => onSelectTrustItem(item)}
                        aria-label={`${item.label}: открыть объяснение`}
                    >
                        <span aria-hidden="true">{item.icon}</span>
                        {item.label}
                    </button>
                ))}
            </div>
        </header>
    )
}
