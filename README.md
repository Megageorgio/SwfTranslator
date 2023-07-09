# SwfTranslator
Translate swf files with DeepL

## Usage
Translate swf automatically
```
java -jar SwfTranslator.jar translate "test.swf" -k <DeepL auth key> -t ru
```

Parse text from swf to json, translate json with DeepL (and then edit it manually), add changes to swf
```
java -jar SwfTranslator.jar parse "test.swf"
java -jar SwfTranslator.jar translate "test.json" -k <DeepL auth key> -s en-US -t ru
java -jar SwfTranslator.jar patch "test.swf" "test-translated.json" -f Arial
```
