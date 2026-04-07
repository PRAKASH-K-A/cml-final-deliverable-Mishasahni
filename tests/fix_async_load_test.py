import argparse
import asyncio
import json
import statistics
import time
from dataclasses import dataclass, field
from typing import List, Optional
from pathlib import Path

import simplefix


@dataclass
class TestConfig:
    host: str = "127.0.0.1"
    port: int = 9878
    users: int = 1000
    target_comp_id: str = "EXCHANGE_SVR"
    symbol: str = "AAPL"
    qty: int = 100
    price: float = 150.00
    concurrency_limit: int = 250
    connect_timeout: float = 5.0
    read_timeout: float = 2.0
    session_hold_seconds: float = 0.2
    startup_jitter_ms: int = 30
    max_session_retries: int = 2
    output_file: str = "loadtest-dashboard/public/loadtest-results.json"


@dataclass
class UserResult:
    user_id: int
    sender_id: str
    connected: bool = False
    logged_on: bool = False
    orders_sent: int = 0
    order_acks: int = 0
    order_rejects: int = 0
    latency_issues: int = 0
    connect_latency_ms: Optional[float] = None
    logon_ack_latency_ms: Optional[float] = None
    order_ack_latencies_ms: List[float] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)


def now_ms() -> float:
    return time.perf_counter() * 1000.0


def make_logon(sender_id: str, target_comp_id: str, seq_num: int) -> simplefix.FixMessage:
    msg = simplefix.FixMessage()
    msg.append_pair(8, "FIX.4.4")
    msg.append_pair(35, "A")
    msg.append_pair(49, sender_id)
    msg.append_pair(56, target_comp_id)
    msg.append_pair(34, seq_num)
    msg.append_utc_timestamp(52)
    msg.append_pair(98, 0)
    msg.append_pair(108, 30)
    return msg


def make_logout(sender_id: str, target_comp_id: str, seq_num: int) -> simplefix.FixMessage:
    msg = simplefix.FixMessage()
    msg.append_pair(8, "FIX.4.4")
    msg.append_pair(35, "5")
    msg.append_pair(49, sender_id)
    msg.append_pair(56, target_comp_id)
    msg.append_pair(34, seq_num)
    msg.append_utc_timestamp(52)
    return msg


def make_new_order(
    sender_id: str,
    target_comp_id: str,
    seq_num: int,
    cl_ord_id: str,
    symbol: str,
    side: str,
    qty: int,
    price: float,
) -> simplefix.FixMessage:
    msg = simplefix.FixMessage()
    msg.append_pair(8, "FIX.4.4")
    msg.append_pair(35, "D")
    msg.append_pair(49, sender_id)
    msg.append_pair(56, target_comp_id)
    msg.append_pair(34, seq_num)
    msg.append_utc_timestamp(52)
    msg.append_pair(11, cl_ord_id)
    msg.append_pair(55, symbol)
    msg.append_pair(54, side)
    msg.append_pair(38, qty)
    msg.append_pair(40, 2)
    msg.append_pair(44, f"{price:.2f}")
    return msg


def get_tag(msg: simplefix.FixMessage, tag: int) -> Optional[str]:
    value = msg.get(tag)
    if value is None:
        return None
    if isinstance(value, bytes):
        return value.decode("ascii", errors="ignore")
    return str(value)


async def read_fix_message(
    reader: asyncio.StreamReader,
    parser: simplefix.FixParser,
    timeout: float,
) -> simplefix.FixMessage:
    while True:
        buffered = parser.get_message()
        if buffered is not None:
            return buffered

        chunk = await asyncio.wait_for(reader.read(4096), timeout=timeout)
        if not chunk:
            raise ConnectionError("Socket closed by peer")
        parser.append_buffer(chunk)


async def read_until_msg_type(
    reader: asyncio.StreamReader,
    parser: simplefix.FixParser,
    msg_types: set,
    timeout: float,
) -> simplefix.FixMessage:
    deadline = time.perf_counter() + timeout
    while True:
        remaining = deadline - time.perf_counter()
        if remaining <= 0:
            raise TimeoutError(f"Timed out waiting for MsgType in {msg_types}")

        msg = await read_fix_message(reader, parser, remaining)
        msg_type = get_tag(msg, 35)
        if msg_type in msg_types:
            return msg


async def safe_send(writer: asyncio.StreamWriter, msg: simplefix.FixMessage, timeout: float) -> None:
    writer.write(msg.encode())
    await asyncio.wait_for(writer.drain(), timeout=timeout)


async def simulate_user(user_id: int, config: TestConfig, gate: asyncio.Semaphore) -> UserResult:
    sender_id = f"USER_{user_id:04d}"
    result = UserResult(user_id=user_id, sender_id=sender_id)

    if config.startup_jitter_ms > 0:
        await asyncio.sleep((user_id % config.startup_jitter_ms) / 1000.0)

    async with gate:
        attempt = 0
        while attempt <= config.max_session_retries:
            reader = None
            writer = None
            try:
                connect_start = now_ms()
                reader, writer = await asyncio.wait_for(
                    asyncio.open_connection(config.host, config.port),
                    timeout=config.connect_timeout,
                )
                result.connected = True
                result.connect_latency_ms = now_ms() - connect_start

                parser = simplefix.FixParser()
                seq = 1

                logon = make_logon(sender_id, config.target_comp_id, seq)
                seq += 1
                logon_send_ts = now_ms()
                await safe_send(writer, logon, config.read_timeout)

                try:
                    await read_until_msg_type(reader, parser, {"A", "3"}, config.read_timeout)
                    result.logged_on = True
                    result.logon_ack_latency_ms = now_ms() - logon_send_ts
                except TimeoutError:
                    result.latency_issues += 1
                    result.errors.append("Logon ack timeout")
                    raise

                for side in ("1", "2"):
                    cl_ord_id = f"CLID_{sender_id}_{side}_{int(time.time() * 1000)}"
                    order = make_new_order(
                        sender_id=sender_id,
                        target_comp_id=config.target_comp_id,
                        seq_num=seq,
                        cl_ord_id=cl_ord_id,
                        symbol=config.symbol,
                        side=side,
                        qty=config.qty,
                        price=config.price,
                    )
                    seq += 1

                    send_ts = now_ms()
                    await safe_send(writer, order, config.read_timeout)
                    result.orders_sent += 1

                    try:
                        ack = await read_until_msg_type(reader, parser, {"8", "9", "3", "j"}, config.read_timeout)
                        msg_type = get_tag(ack, 35)
                        latency_ms = now_ms() - send_ts
                        result.order_ack_latencies_ms.append(latency_ms)

                        if msg_type == "8":
                            exec_type = get_tag(ack, 150)
                            ord_status = get_tag(ack, 39)
                            if exec_type == "8" or ord_status == "8":
                                result.order_rejects += 1
                            else:
                                result.order_acks += 1
                        elif msg_type in {"3", "9", "j"}:
                            result.order_rejects += 1
                    except TimeoutError:
                        result.latency_issues += 1
                        result.errors.append(f"Order ack timeout side={side}")

                await asyncio.sleep(config.session_hold_seconds)

                try:
                    logout = make_logout(sender_id, config.target_comp_id, seq)
                    await safe_send(writer, logout, config.read_timeout)
                except Exception:
                    pass

                writer.close()
                await writer.wait_closed()
                return result

            except Exception as exc:
                result.errors.append(str(exc))
                result.latency_issues += 1
                attempt += 1
                if writer is not None:
                    try:
                        writer.close()
                        await writer.wait_closed()
                    except Exception:
                        pass

                if attempt <= config.max_session_retries:
                    await asyncio.sleep(0.05 * attempt)
                else:
                    return result

    return result


def pct(values: List[float], percentile: float) -> Optional[float]:
    if not values:
        return None
    data = sorted(values)
    k = (len(data) - 1) * (percentile / 100.0)
    f = int(k)
    c = min(f + 1, len(data) - 1)
    if f == c:
        return data[f]
    return data[f] + (data[c] - data[f]) * (k - f)


def print_summary(results: List[UserResult], elapsed_s: float) -> None:
    total_users = len(results)
    connected = sum(1 for r in results if r.connected)
    logged_on = sum(1 for r in results if r.logged_on)
    orders_sent = sum(r.orders_sent for r in results)
    order_acks = sum(r.order_acks for r in results)
    order_rejects = sum(r.order_rejects for r in results)
    latency_issues = sum(r.latency_issues for r in results)

    connect_lat = [r.connect_latency_ms for r in results if r.connect_latency_ms is not None]
    logon_lat = [r.logon_ack_latency_ms for r in results if r.logon_ack_latency_ms is not None]
    order_lat = [x for r in results for x in r.order_ack_latencies_ms]

    print("\n=== FIX Async Load Test Summary ===")
    print(f"Users requested:     {total_users}")
    print(f"Users connected:     {connected}")
    print(f"Users logged on:     {logged_on}")
    print(f"Orders sent:         {orders_sent}")
    print(f"Order acks:          {order_acks}")
    print(f"Order rejects:       {order_rejects}")
    print(f"Latency issues:      {latency_issues}")
    print(f"Total elapsed:       {elapsed_s:.2f}s")

    if elapsed_s > 0:
        print(f"Submit throughput:   {orders_sent / elapsed_s:.2f} orders/sec")
        print(f"Ack throughput:      {order_acks / elapsed_s:.2f} acks/sec")

    def line(label: str, values: List[float]) -> None:
        if not values:
            print(f"{label:<20} n/a")
            return
        p50 = pct(values, 50)
        p95 = pct(values, 95)
        p99 = pct(values, 99)
        avg = statistics.fmean(values)
        print(
            f"{label:<20} avg={avg:.2f}ms p50={p50:.2f}ms p95={p95:.2f}ms p99={p99:.2f}ms max={max(values):.2f}ms"
        )

    print("\nLatency metrics:")
    line("Connect", connect_lat)
    line("Logon Ack", logon_lat)
    line("Order Ack", order_lat)

    failed_users = [r for r in results if r.orders_sent < 2]
    if failed_users:
        print(f"\nUsers with partial/failed sessions: {len(failed_users)}")
        for r in failed_users[:10]:
            last_error = r.errors[-1] if r.errors else "unknown"
            print(f"  - {r.sender_id}: sent={r.orders_sent}, latency_issues={r.latency_issues}, last_error={last_error}")


def save_summary_json(results: List[UserResult], elapsed_s: float, config: TestConfig, output_file: str = "results.json") -> None:
    """Save test results as JSON for dashboard consumption."""
    total_users = len(results)
    connected = sum(1 for r in results if r.connected)
    logged_on = sum(1 for r in results if r.logged_on)
    orders_sent = sum(r.orders_sent for r in results)
    order_acks = sum(r.order_acks for r in results)
    order_rejects = sum(r.order_rejects for r in results)
    latency_issues = sum(r.latency_issues for r in results)

    connect_lat = [r.connect_latency_ms for r in results if r.connect_latency_ms is not None]
    logon_lat = [r.logon_ack_latency_ms for r in results if r.logon_ack_latency_ms is not None]
    order_lat = [x for r in results for x in r.order_ack_latencies_ms]

    def get_percentile(values: List[float], percentile: float) -> Optional[float]:
        return pct(values, percentile)

    # Build latency distribution histogram
    latency_dist = [
        {"bucket": "<100ms", "value": sum(1 for x in order_lat if x < 100)},
        {"bucket": "100-300ms", "value": sum(1 for x in order_lat if 100 <= x < 300)},
        {"bucket": "300-500ms", "value": sum(1 for x in order_lat if 300 <= x < 500)},
        {"bucket": ">500ms", "value": sum(1 for x in order_lat if x >= 500)},
    ]

    # Extract failed user details
    failed_rows = []
    for r in results:
        if r.orders_sent < 2:
            last_error = r.errors[-1] if r.errors else "unknown"
            severity = "critical" if r.latency_issues > 2 else ("high" if r.orders_sent == 0 else "medium")
            failed_rows.append({
                "user": r.sender_id,
                "error": last_error,
                "severity": severity,
                "ordersSent": r.orders_sent,
                "latencyIssues": r.latency_issues,
            })

    summary = {
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "users": total_users,
        "connected": connected,
        "ordersSent": orders_sent,
        "ordersAcked": order_acks,
        "orderRejects": order_rejects,
        "latencyIssues": latency_issues,
        "retries": sum(len(r.errors) for r in results),
        "elapsedSec": round(elapsed_s, 1),
        "throughput": round(orders_sent / elapsed_s, 2) if elapsed_s > 0 else 0,
        "connectionRate": round((connected / total_users) * 100, 1) if total_users > 0 else 0,
        "orderAckRate": round((order_acks / orders_sent) * 100, 1) if orders_sent > 0 else 0,
        "latency": {
            "connect": {
                "p50": round(get_percentile(connect_lat, 50) or 0, 2),
                "p95": round(get_percentile(connect_lat, 95) or 0, 2),
                "p99": round(get_percentile(connect_lat, 99) or 0, 2),
            },
            "logonAck": {
                "p50": round(get_percentile(logon_lat, 50) or 0, 2),
                "p95": round(get_percentile(logon_lat, 95) or 0, 2),
                "p99": round(get_percentile(logon_lat, 99) or 0, 2),
            },
            "orderAck": {
                "p50": round(get_percentile(order_lat, 50) or 0, 2),
                "p95": round(get_percentile(order_lat, 95) or 0, 2),
                "p99": round(get_percentile(order_lat, 99) or 0, 2),
            },
        },
        "config": {
            "host": config.host,
            "port": config.port,
            "symbol": config.symbol,
            "targetCompId": config.target_comp_id,
        },
        "kpiTrend": {
            "users": "+0%",
            "connected": f"{((connected / total_users) * 100) - 100:.0f}%",
            "throughput": f"{((orders_sent / elapsed_s - 0) / 1) * 100:.0f}%" if elapsed_s > 0 else "0%",
            "latency": f"+{latency_issues}",
        },
        "latencyDist": latency_dist,
        "failedRows": failed_rows,
    }

    # Ensure output directory exists
    Path(output_file).parent.mkdir(parents=True, exist_ok=True)
    with open(output_file, "w") as f:
        json.dump(summary, f, indent=2)
    print(f"\nJSON results saved to: {output_file}")


async def run_test(config: TestConfig) -> None:
    print(
        f"Starting FIX async emulation: users={config.users}, host={config.host}:{config.port}, "
        f"targetCompId={config.target_comp_id}, symbol={config.symbol}"
    )

    gate = asyncio.Semaphore(config.concurrency_limit)
    start = time.perf_counter()

    tasks = [simulate_user(i, config, gate) for i in range(config.users)]
    results = await asyncio.gather(*tasks)

    elapsed = time.perf_counter() - start
    print_summary(results, elapsed)
    save_summary_json(results, elapsed, config, config.output_file)


def parse_args() -> TestConfig:
    parser = argparse.ArgumentParser(description="Async FIX load test with 1000 opposing buy/sell users")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9878)
    parser.add_argument("--users", type=int, default=1000)
    parser.add_argument("--target-comp-id", default="EXCHANGE_SVR")
    parser.add_argument("--symbol", default="AAPL")
    parser.add_argument("--qty", type=int, default=100)
    parser.add_argument("--price", type=float, default=150.00)
    parser.add_argument("--concurrency-limit", type=int, default=250)
    parser.add_argument("--connect-timeout", type=float, default=5.0)
    parser.add_argument("--read-timeout", type=float, default=2.0)
    parser.add_argument("--session-hold-seconds", type=float, default=0.2)
    parser.add_argument("--startup-jitter-ms", type=int, default=30)
    parser.add_argument("--max-session-retries", type=int, default=2)
    parser.add_argument("--output-file", default="loadtest-dashboard/public/loadtest-results.json")

    args = parser.parse_args()
    return TestConfig(
        host=args.host,
        port=args.port,
        users=args.users,
        target_comp_id=args.target_comp_id,
        symbol=args.symbol,
        qty=args.qty,
        price=args.price,
        concurrency_limit=args.concurrency_limit,
        connect_timeout=args.connect_timeout,
        read_timeout=args.read_timeout,
        session_hold_seconds=args.session_hold_seconds,
        startup_jitter_ms=args.startup_jitter_ms,
        max_session_retries=args.max_session_retries,
        output_file=args.output_file,
    )


if __name__ == "__main__":
    cfg = parse_args()
    asyncio.run(run_test(cfg))
