"""
B 站会话信息提取工具。
从 HTTP 请求报文文件中提取 Cookie，保存为 JSON 配置文件。
"""

import argparse
import json
import re
from pathlib import Path


def extract_cookies(request_file: Path) -> dict:
    """从请求报文文件中提取 Cookie"""
    content = request_file.read_text(encoding='utf-8')
    
    # 提取 Cookie 行
    cookie_match = re.search(r'cookie: (.+?)(?:\n|$)', content, re.IGNORECASE)
    if not cookie_match:
        raise ValueError("未找到 Cookie 信息")
    
    cookie_str = cookie_match.group(1)
    cookies = {}
    for item in cookie_str.split('; '):
        if '=' in item:
            key, value = item.split('=', 1)
            cookies[key] = value
    
    return cookies


def validate_session(cookies: dict) -> dict:
    """验证并提取关键会话字段"""
    required = ['SESSDATA', 'bili_jct']
    missing = [k for k in required if k not in cookies]
    if missing:
        raise ValueError(f"缺少必需的 Cookie 字段: {missing}")
    
    return {
        'SESSDATA': cookies['SESSDATA'],
        'bili_jct': cookies['bili_jct'],
        'DedeUserID': cookies.get('DedeUserID', ''),
        'buvid3': cookies.get('buvid3', ''),
    }


def main():
    parser = argparse.ArgumentParser(description='从 B 站请求报文提取会话信息')
    parser.add_argument('--request-file', required=True, help='HTTP 请求报文文件路径')
    parser.add_argument('--output', required=True, help='输出 JSON 文件路径')
    args = parser.parse_args()
    
    request_file = Path(args.request_file)
    output_file = Path(args.output)
    
    # 提取 Cookie
    cookies = extract_cookies(request_file)
    
    # 验证并提取关键字段
    session = validate_session(cookies)
    
    # 保存到 JSON 文件
    output_file.parent.mkdir(parents=True, exist_ok=True)
    output_file.write_text(json.dumps(session, indent=2), encoding='utf-8')
    
    print(f"会话信息已保存到: {output_file}")
    print(f"提取的字段: {list(session.keys())}")


if __name__ == '__main__':
    main()
