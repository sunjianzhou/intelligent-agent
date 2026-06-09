from setuptools import setup, find_packages

setup(
    name="intelligent-agent",
    version="0.1.0",
    packages=find_packages(where="src"),
    package_dir={"": "src"},
    install_requires=[
        "python-dotenv>=1.0.0",
        "pydantic>=2.0.0",
        "pydantic-settings>=2.0.0",
        "loguru>=0.7.0",
        "fastapi>=0.104.0",
        "uvicorn>=0.24.0",
        "ollama>=0.1.8",
    ],
)