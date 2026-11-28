"""Run a reading round through a real process restart on a guest QA emulator."""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree

parser=argparse.ArgumentParser()
parser.add_argument("adb")
parser.add_argument("serial")
parser.add_argument("out",type=Path)
args=parser.parse_args()
assert args.serial.startswith("emulator-")
args.out.mkdir(parents=True,exist_ok=True)
package="org.lexora.study"
def adb(*parts):
    return subprocess.check_output([args.adb,"-s",args.serial,*parts],timeout=35)
def launch():
    adb("shell","am","start","-W","-n",package+"/.MainActivity")
def tree():
    adb("shell","uiautomator","dump","/sdcard/lexora-v6-restart.xml")
    return ElementTree.fromstring(adb("shell","cat","/sdcard/lexora-v6-restart.xml"))
def find(label,click=True):
    for n in range(20):
        nodes=list(tree().iter("node"))
        for node in nodes:
            value=node.get("text","")
            if value not in {label,"Start at A1"}:
                continue
            x,y,x2,y2=map(int,re.findall(r"\d+",node.get("bounds","")))
            if x2<=x or y2<=y:
                continue
            if click or value=="Start at A1":
                adb("shell","input","tap",str((x+x2)//2),str((y+y2)//2))
                time.sleep(0.35)
            if value==label:
                return
            break
        else:
            if n in (0,1):
                time.sleep(0.4)
            elif n==11:
                for _ in range(6):
                    adb("shell","input","swipe","210","210","210","695","140")
            else:
                adb("shell","input","swipe","210","680","210","275","300")
    raise AssertionError((label,[n.get("text") for n in nodes if n.get("text")]))
def shot(name):
    (args.out/(name+".png")).write_bytes(adb("exec-out","screencap","-p"))
def open_round():
    find("Learning Academy");find("Reading Academy")
    find("Read Der zweite Schlüssel");find("Start comprehension")
adb("shell","am","force-stop",package)
launch()
find("Your study day",False);shot("home")
open_round()
find("Inside the flat");find("Next question",False)
adb("shell","am","force-stop",package)
launch()
open_round()
find("Next question");find("Flowers");find("Finish round");find("Round complete",False)
shot("completed-reading")
(args.out/"restart.json").write_text(json.dumps({"passage":"story-1","restart_after":"submitted first answer",
    "resumed_at":"first answer feedback","completed_second_question":True},indent=2))
print("Reading answer survived force-stop. Resumed and finished both questions.")
