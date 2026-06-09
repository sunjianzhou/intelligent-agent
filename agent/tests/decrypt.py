import base64
import hashlib
from Crypto.Cipher import AES


def evp_bytes_to_key(password, salt, key_len=32, iv_len=16):
    """OpenSSL EVP_BytesToKey 算法实现"""
    d = b''
    prev = b''
    while len(d) < key_len + iv_len:
        prev = hashlib.md5(prev + password + salt).digest()
        d += prev
    return d[:key_len], d[key_len:key_len + iv_len]


def decrypt_openssl_file(file_path, password):
    """解密 OpenSSL 加密的文件"""
    try:
        # 读取并 Base64 解码
        with open(file_path, 'rb') as f:
            encrypted_data = base64.b64decode(f.read().strip())

        # 验证 OpenSSL 加密格式
        if not encrypted_data.startswith(b'Salted__'):
            raise ValueError("不是有效的 OpenSSL 加密文件")

        # 提取盐和密文
        salt = encrypted_data[8:16]
        ciphertext = encrypted_data[16:]

        # 派生密钥和 IV
        key, iv = evp_bytes_to_key(password.encode('utf-8'), salt)

        # 解密
        cipher = AES.new(key, AES.MODE_CBC, iv)
        plaintext = cipher.decrypt(ciphertext)

        # 移除 PKCS#7 填充
        padding_length = plaintext[-1]
        if padding_length < 1 or padding_length > 16:
            raise ValueError("无效的填充或密码错误")

        plaintext = plaintext[:-padding_length]

        # 尝试解码为 UTF-8
        try:
            return plaintext.decode('utf-8')
        except UnicodeDecodeError:
            # 如果不是文本，返回原始字节
            return plaintext

    except FileNotFoundError:
        raise FileNotFoundError(f"文件不存在: {file_path}")
    except Exception as e:
        raise RuntimeError(f"解密失败: {str(e)}")


if __name__ == "__main__":
    input_file = r"C:\Users\acer\Desktop\feishu\whisper_demo.txt"
    password = "霖君的糖糖"

    try:
        decrypted_text = decrypt_openssl_file(input_file, password)
        print("解密成功！")
        print("=" * 50)
        print(decrypted_text)
        print("=" * 50)

        # 保存到文件
        output_file = r"C:\Users\acer\Desktop\feishu\whisper_decrypted.txt"
        with open(output_file, 'w', encoding='utf-8') as f:
            f.write(decrypted_text)
        print(f"\n已保存到: {output_file}")

    except Exception as e:
        print(f"错误: {e}")