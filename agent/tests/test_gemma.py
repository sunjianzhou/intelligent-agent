import requests, json

def test_gemma_chat():
    print("\n开始测试")
    url = 'http://203.83.236.77:8000/v1/chat/completions'
    headers = {
        'Authorization': 'Bearer Sfit_Gemma4',
        'Content-Type': 'application/json'
    }
    body = {
        'model': 'gemma4-31B',
        'messages': [{'role': 'user', 'content': '你好，当前你是什么模型'}],
        'max_tokens': 10240,
        'stream': False
    }

    try:
        r = requests.post(url, headers=headers, json=body, timeout=300)
        print('状态码:', r.status_code)
        print('响应:', r.text[:500])
    except Exception as e:
        print('请求失败:', e)
    print("测试结束")

if __name__ == "__main__":
    test_gemma_chat()