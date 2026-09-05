import os
import time
import json
import hmac
import hashlib
from pathlib import Path
from typing import Any, Dict, Optional
import requests
from dotenv import load_dotenv

# Load .env from project root
env_path = Path(__file__).resolve().parent.parent.parent / '.env'
load_dotenv(dotenv_path=env_path)

BASE_URL = "https://api.coindcx.com"
PUBLIC_URL = "https://public.coindcx.com"

class CoinDCXClient:
    def __init__(self, api_key: Optional[str] = None, api_secret: Optional[str] = None):
        self.api_key = api_key or os.getenv("COINDCX_API_KEY", "").strip()
        self.api_secret = api_secret or os.getenv("COINDCX_API_SECRET", "").strip()
        self.session = requests.Session()

    def is_configured(self) -> bool:
        return bool(self.api_key and self.api_secret and self.api_key != "your_api_key_here")

    def public_get(self, endpoint: str, params: Optional[Dict[str, Any]] = None) -> requests.Response:
        """Fetch from public endpoints"""
        url = f"{BASE_URL}{endpoint}" if endpoint.startswith("/exchange") else f"{PUBLIC_URL}{endpoint}"
        return self.session.get(url, params=params, timeout=10)

    def send_signed_get(self, endpoint: str, params: Optional[Dict[str, Any]] = None) -> requests.Response:
        """Send authenticated GET request signed with HMAC-SHA256"""
        if not self.is_configured():
            raise ValueError("API Key or Secret missing. Please update .env file.")

        p = params.copy() if params else {}
        if "timestamp" not in p:
            p["timestamp"] = int(time.time())

        secret_bytes = bytes(self.api_secret, 'utf-8')
        signature = hmac.new(secret_bytes, b'', hashlib.sha256).hexdigest()

        headers = {
            "X-AUTH-APIKEY": self.api_key,
            "X-AUTH-SIGNATURE": signature
        }

        url = f"{BASE_URL}{endpoint}"
        return self.session.get(url, params=p, headers=headers, timeout=10)

    def send_signed_post(self, endpoint: str, payload: Optional[Dict[str, Any]] = None) -> requests.Response:
        """Send authenticated POST request signed with HMAC-SHA256"""
        if not self.is_configured():
            raise ValueError("API Key or Secret missing. Please update .env file.")

        body = payload.copy() if payload else {}
        # Mandatory timestamp in milliseconds
        body["timestamp"] = int(round(time.time() * 1000))

        # CoinDCX requires compact JSON serialization matching signature
        json_body = json.dumps(body, separators=(',', ':'))
        secret_bytes = bytes(self.api_secret, 'utf-8')
        signature = hmac.new(secret_bytes, json_body.encode('utf-8'), hashlib.sha256).hexdigest()

        headers = {
            "Content-Type": "application/json",
            "X-AUTH-APIKEY": self.api_key,
            "X-AUTH-SIGNATURE": signature
        }

        url = f"{BASE_URL}{endpoint}"
        return self.session.post(url, data=json_body, headers=headers, timeout=10)
