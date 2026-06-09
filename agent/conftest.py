import sys
import os

# Make the agent package root importable when running pytest from agent/
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
