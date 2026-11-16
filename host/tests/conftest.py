import pytest

from lightgun_host import emufix


@pytest.fixture(autouse=True)
def _backups_in_tmp(tmp_path, monkeypatch):
    """Keep test backups out of the real PhoneBlaster settings folder."""
    monkeypatch.setattr(emufix, "BACKUP_ROOT", tmp_path / "_backups")
