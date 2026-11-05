/** Settings/secret fields per provider (mirrors the backend validators). Unknown providers fall back to JSON. */
export interface FieldSpec {
  key: string;
  label: string;
  type: 'text' | 'number' | 'boolean' | 'select';
  options?: string[];
  required?: boolean;
}

export interface ProviderSpec {
  channel: string;
  settings: FieldSpec[];
  secrets: { key: string; label: string; file?: boolean }[];
}

export const PROVIDERS: Record<string, ProviderSpec> = {
  SMTP: {
    channel: 'EMAIL',
    settings: [
      { key: 'host', label: 'Host', type: 'text', required: true },
      { key: 'port', label: 'Port', type: 'number', required: true },
      { key: 'tls', label: 'STARTTLS', type: 'boolean' },
      { key: 'from', label: 'From address', type: 'text', required: true },
      { key: 'username', label: 'Username', type: 'text' },
    ],
    secrets: [{ key: 'password', label: 'Password' }],
  },
  GENERIC_HTTP_SMS: {
    channel: 'SMS',
    settings: [
      { key: 'endpoint', label: 'Endpoint URL', type: 'text', required: true },
      { key: 'sender', label: 'Sender / brandname', type: 'text', required: true },
    ],
    secrets: [
      { key: 'api_key', label: 'API key' },
      { key: 'api_secret', label: 'API secret' },
      { key: 'callback_hmac_secret', label: 'Callback HMAC secret' },
    ],
  },
  ANDROID_SMS_GATEWAY: {
    channel: 'SMS',
    settings: [
      { key: 'mode', label: 'Mode', type: 'select', options: ['cloud', 'local'], required: true },
      { key: 'base_url', label: 'Base URL (local mode)', type: 'text' },
    ],
    secrets: [
      { key: 'username', label: 'Username' },
      { key: 'password', label: 'Password' },
      { key: 'webhook_signing_key', label: 'Webhook signing key' },
    ],
  },
  FCM: {
    channel: 'PUSH',
    settings: [
      { key: 'project_id', label: 'Firebase project ID', type: 'text', required: true },
      { key: 'base_url', label: 'API base URL (optional)', type: 'text' },
    ],
    secrets: [{ key: 'service_account_json', label: 'Service account JSON', file: true }],
  },
};
