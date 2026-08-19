.PHONY: format lint test package

format:
	sh scripts/format.sh

lint:
	sh scripts/lint.sh

test:
	sh scripts/test.sh

package:
	sh scripts/package.sh 0.1.0
