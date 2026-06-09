import sys
from loguru import logger
from pathlib import Path
from config.settings import settings


def setup_logger():
    """配置日志系统"""

    # 移除默认处理器
    logger.remove()

    # 控制台输出
    logger.add(
        sys.stderr,
        format="<green>{time:YYYY-MM-DD HH:mm:ss}</green> | <level>{level: <8}</level> | <cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> - <level>{message}</level>",
        level=settings.log_level.upper(),
        colorize=True,
    )

    # 文件输出
    log_path = settings.project_root / "logs"
    log_path.mkdir(exist_ok=True)

    logger.add(
        log_path / "app_{time:YYYY-MM-DD}.log",
        format="{time:YYYY-MM-DD HH:mm:ss} | {level: <8} | {name}:{function}:{line} - {message}",
        level=settings.log_level.upper(),
        rotation="1 day",
        retention="30 days",
        compression="zip",
    )

    return logger


# 创建全局logger实例
log = setup_logger()