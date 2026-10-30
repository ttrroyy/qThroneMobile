"""Select the separately pinned compatibility client; preserve the Legacy bridge."""
from pathlib import Path
import sys

p = Path(sys.argv[1]) / "csqtt_bridge.go"
text = p.read_text(encoding="utf-8")
before = 'command := exec.Command(filepath.Join(filepath.Dir(exe), name))'
after = '''if runtime.GOOS == "android" {
        name, err = mobileCSQTTTransportName(path)
        if err != nil { return err }
    }
    command := exec.Command(filepath.Join(filepath.Dir(exe), name))'''
assert text.count(before) == 1, "CSQTT bridge launch changed"
text = text.replace(before, after)
before = "line := scanner.Text()"
assert text.count(before) == 1, "CSQTT output scanner changed"
text = text.replace(before, before + "\n        if hint := mobileCSQTTHint(line); hint != \"\" { fmt.Println(hint) }")
p.write_text(text, encoding="utf-8", newline="\n")
