"""教学体系 REST API（/api/teaching/*）。"""
from typing import List, Optional
from fastapi import APIRouter
from pydantic import BaseModel
from loguru import logger

from teaching.question_bank import get_questions
from teaching.daily_plan import get_today_plan
from teaching.grader import grade, Submission, Answer
from teaching import wrong_book
from teaching import command_log

router = APIRouter(prefix="/api/teaching", tags=["teaching"])


# ── 每日计划 ─────────────────────────────────────────────────────────────────

@router.get("/daily-plan")
def daily_plan_endpoint(topic: str = "k8s"):
    plan = get_today_plan(topic)
    return {
        "date": plan.date,
        "topic": plan.topic,
        "is_weekend": plan.is_weekend,
        "questions": [
            {
                "id": q.id,
                "type": q.type,
                "difficulty": q.difficulty,
                "text": q.text,
                "options": q.options,
            }
            for q in plan.questions
        ],
        "commands": plan.commands,
    }


# ── 提交批改 ─────────────────────────────────────────────────────────────────

class AnswerItem(BaseModel):
    question_id: str
    user_answer: str


class SubmitRequest(BaseModel):
    user_id: str = "default"
    topic: str = "k8s"
    answers: List[AnswerItem]


@router.post("/submit")
def submit_answers(req: SubmitRequest):
    submission = Submission(
        user_id=req.user_id,
        topic=req.topic,
        answers=[Answer(question_id=a.question_id, user_answer=a.user_answer) for a in req.answers],
    )
    result = grade(submission)
    return {
        "score": result.score,
        "total": result.total,
        "results": [
            {
                "question_id": r.question_id,
                "correct": r.correct,
                "user_answer": r.user_answer,
                "correct_answer": r.correct_answer,
                "explanation": r.explanation,
            }
            for r in result.results
        ],
    }


# ── 错题本 ────────────────────────────────────────────────────────────────────

@router.get("/wrong-book")
def get_wrong_book(
    topic: str = "k8s",
    device: Optional[str] = None,
    include_resolved: bool = False,
):
    records = wrong_book.list_records(topic=topic, include_resolved=include_resolved)
    return {"topic": topic, "device": device, "records": records, "count": len(records)}


@router.post("/wrong-book/{question_id}/resolve")
def resolve_wrong(question_id: str, topic: str = "k8s"):
    ok = wrong_book.resolve(question_id=question_id, topic=topic)
    return {"success": ok, "question_id": question_id}


# ── 命令积累 ──────────────────────────────────────────────────────────────────

@router.get("/command-log")
def get_command_log(topic: str = "k8s"):
    path = command_log._path(topic)
    if not path.exists():
        return {"topic": topic, "content": ""}
    return {"topic": topic, "content": path.read_text(encoding="utf-8")}
