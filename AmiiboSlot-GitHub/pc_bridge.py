"""Local serial bridge for MuMuPlayer. Usage: python pc_bridge.py COM6

Bind to loopback only. Run `adb -s 127.0.0.1:16384 reverse tcp:8765 tcp:8765`
once after MuMu starts. The app talks to 127.0.0.1:8765 inside Android.
"""
import argparse
import socket
import threading
import serial

def copy_socket_to_serial(client, device, stop):
    try:
        while not stop.is_set():
            payload = client.recv(4096)
            if not payload:
                break
            device.write(payload)
    except (OSError, serial.SerialException):
        pass
    finally:
        stop.set()

def copy_serial_to_socket(client, device, stop):
    try:
        while not stop.is_set():
            payload = device.read(4096)
            if payload:
                client.sendall(payload)
    except (OSError, serial.SerialException):
        pass
    finally:
        stop.set()

def main():
    parser = argparse.ArgumentParser(description='Local USB bridge for Amiibo Slot')
    parser.add_argument('port', nargs='?', default='COM6')
    args = parser.parse_args()
    with serial.Serial(args.port, baudrate=115200, timeout=0.1) as device:
        try:
            device.dtr = True
        except (OSError, ValueError):
            pass
        with socket.socket() as server:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(('127.0.0.1', 8765))
            server.listen(1)
            print(f'Chameleon on {args.port}; bridge listening at 127.0.0.1:8765', flush=True)
            while True:
                client, address = server.accept()
                print('Android connected', flush=True)
                stop = threading.Event()
                with client:
                    send = threading.Thread(target=copy_serial_to_socket,args=(client,device,stop),daemon=True)
                    send.start()
                    copy_socket_to_serial(client,device,stop)
                    stop.set()
                    send.join(timeout=1)
                print('Android disconnected', flush=True)

if __name__ == '__main__':
    main()
