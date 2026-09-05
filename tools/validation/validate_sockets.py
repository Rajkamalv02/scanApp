import time
import json
import socketio

def test_socket_stream():
    print("[*] Connecting to CoinDCX Real-Time Stream (wss://stream.coindcx.com)...")
    sio = socketio.Client()
    received_events = []

    @sio.event
    def connect():
        print("    [CONNECTED] Socket session established successfully!")
        target_pair = "B-BTC_USDT"
        print(f"    [SUBSCRIBING] Joining public channel: {target_pair}")
        sio.emit('join', {'channelName': target_pair})

    @sio.on('*')
    def on_any_event(event, data):
        received_events.append((event, data))
        data_str = str(data)
        if len(data_str) > 120:
            data_str = data_str[:120] + "..."
        print(f"    [STREAM EVENT: {event}] {data_str}")

    try:
        sio.connect('wss://stream.coindcx.com', transports=['websocket'])
        print("[*] Streaming live market data for 5 seconds...")
        time.sleep(5)
        sio.disconnect()
        print(f"\n[SUCCESS] Stream test passed! Captured {len(received_events)} live events.")
    except Exception as e:
        print(f"[FAILED] Stream error: {e}")

if __name__ == "__main__":
    test_socket_stream()
