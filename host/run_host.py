"""Entry point for the frozen (PyInstaller) build.

`lightgun_host/__main__.py` uses a relative import (`from .app import main`), which only
works when Python imports the package normally (`python -m lightgun_host`). PyInstaller
runs its entry script directly rather than as a package submodule, so that relative
import fails in the frozen build with "attempted relative import with no known parent
package". This wrapper uses an absolute import instead and is what pyinstaller.spec
points at; `python -m lightgun_host` (unfrozen, normal dev use) is unaffected.
"""
from lightgun_host.app import main

if __name__ == "__main__":
    main()
