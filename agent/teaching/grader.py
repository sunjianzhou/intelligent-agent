"""批改引擎：三题型，对题也解析，错题自动归档。"""
from dataclasses import dataclass, field
from typing import List
from teaching.question_bank import Question, get_questions
from teaching import wrong_book


@dataclass
class Answer:
    question_id: str
    user_answer: str


@dataclass
class QuestionResult:
    question_id: str
    correct: bool
    user_answer: str
    correct_answer: str
    explanation: str    # always populated (v4.6 铁律)


@dataclass
class Submission:
    user_id: str
    topic: str
    answers: List[Answer]


@dataclass
class GradeResult:
    user_id: str
    topic: str
    score: int
    total: int
    results: List[QuestionResult] = field(default_factory=list)


def grade(submission: Submission) -> GradeResult:
    question_map = {
        q.id: q
        for q in get_questions(topic=submission.topic)
    }
    results: List[QuestionResult] = []
    for answer in submission.answers:
        q = question_map.get(answer.question_id)
        if q is None:
            continue
        correct = _check_answer(q, answer.user_answer)
        if not correct:
            wrong_book.add(
                question_id=q.id,
                topic=submission.topic,
                user_answer=answer.user_answer,
                correct_answer=q.answer,
            )
        results.append(QuestionResult(
            question_id=q.id,
            correct=correct,
            user_answer=answer.user_answer,
            correct_answer=q.answer,
            explanation=q.explanation,
        ))
    return GradeResult(
        user_id=submission.user_id,
        topic=submission.topic,
        score=sum(1 for r in results if r.correct),
        total=len(results),
        results=results,
    )


def _check_answer(q: Question, user_answer: str) -> bool:
    if q.type == "choice":
        return user_answer.strip().upper() == q.answer.strip().upper()
    if q.type == "fill":
        return user_answer.strip().lower() == q.answer.strip().lower()
    if q.type == "short_answer":
        key_terms = [t for t in q.answer.lower().split() if len(t) > 2]
        if not key_terms:
            return True
        user_lower = user_answer.lower()
        matched = sum(1 for t in key_terms if t in user_lower)
        return matched >= max(1, len(key_terms) // 2)
    return False
