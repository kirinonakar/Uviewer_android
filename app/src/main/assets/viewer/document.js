
                    function jumpToBottom() {
                        setTimeout(function() {
                            var spacer = document.getElementById('end-marker');
                            if (spacer) {
                                spacer.scrollIntoView(false);
                            } else {
                                window.scrollTo(0, document.body.scrollHeight);
                            }
                        }, 50); 
                    }

                    function renderMath() {
                        if (typeof renderMathInElement === 'function') {
                            renderMathInElement(document.body, {
                                delimiters: [
                                    {left: "$$", right: "$$", display: true},
                                    {left: "$", right: "$", inline: true},
                                    {left: "\\(", right: "\\)", inline: true},
                                    {left: "\\[", right: "\\]", display: true}
                                ],
                                throwOnError : false
                            });
                        }
                    }

                    window.renderMath = renderMath;

                    window.addEventListener('DOMContentLoaded', function() {
                        renderMath();
                        fixRubySpacing();
                    });
                    

function fixRubySpacing() {
                        // 1단계: 인접한 모든 루비를 무조건 하나로 병합 (조각난 루비 길이 오작동 방지)
                        var rubies = Array.from(document.querySelectorAll('ruby'));
                        for (var i = 0; i < rubies.length; i++) {
                            var ruby = rubies[i];
                            if (!ruby.parentNode) continue; // 이미 앞선 병합 과정에서 삭제된 노드 패스

                            var next = ruby.nextSibling;
                            while (next) {
                                // 의미 없는 빈 공백 텍스트 노드는 무시하고 제거
                                if (next.nodeType === 3 && next.textContent.trim() === '') {
                                    var toRemove = next;
                                    next = next.nextSibling;
                                    toRemove.parentNode.removeChild(toRemove);
                                    continue;
                                }
                                // 다음 노드가 루비라면 내용물 병합
                                if (next.nodeType === 1 && next.tagName === 'RUBY') {
                                    var base1 = Array.from(ruby.childNodes).filter(function(n) { return n.nodeType === 3; }).map(function(n) { return n.textContent; }).join('');
                                    var rt1 = Array.from(ruby.querySelectorAll('rt')).map(function(r) { return r.textContent; }).join('');
                                    
                                    var base2 = Array.from(next.childNodes).filter(function(n) { return n.nodeType === 3; }).map(function(n) { return n.textContent; }).join('');
                                    var rt2 = Array.from(next.querySelectorAll('rt')).map(function(r) { return r.textContent; }).join('');
                                    
                                    ruby.innerHTML = '';
                                    ruby.appendChild(document.createTextNode(base1 + base2));
                                    var newRt = document.createElement('rt');
                                    newRt.textContent = rt1 + rt2;
                                    ruby.appendChild(newRt);
                                    
                                    var toRemove = next;
                                    next = next.nextSibling;
                                    toRemove.parentNode.removeChild(toRemove);
                                } else {
                                    break; // 루비가 아니면 병합 중단
                                }
                            }
                        }

                        // 2단계: 병합이 완료된 루비들을 대상으로 길이 검사 및 앞뒤 1글자 텍스트 흡수
                        var mergedRubies = Array.from(document.querySelectorAll('ruby'));
                        for (var i = 0; i < mergedRubies.length; i++) {
                            var ruby = mergedRubies[i];
                            if (!ruby.parentNode) continue;

                            var baseText = Array.from(ruby.childNodes).filter(function(n) { return n.nodeType === 3; }).map(function(n) { return n.textContent; }).join('');
                            var rubyText = Array.from(ruby.querySelectorAll('rt')).map(function(rt) { return rt.textContent; }).join('');
                            if (rubyText.trim().length === 0) continue;

                            var baseLen = Array.from(baseText.trim()).length;
                            var rubyLen = Array.from(rubyText.trim()).length;

                            // 가로, 세로 공통: 1자에 3자, 2자에 4자, 3자 이상에 5자 이상
                            var needsMerge = (baseLen === 1 && rubyLen >= 3) || 
                                             (baseLen === 2 && rubyLen >= 4) || 
                                             (baseLen >= 3 && rubyLen >= 5);

                            if (needsMerge) {
                                var prev = ruby.previousSibling;
                                var next = ruby.nextSibling;
                                
                                // 앞쪽 일반 텍스트 1자 흡수 (Array.from으로 유니코드 이모지/한자 깨짐 방지)
                                if (prev && prev.nodeType === 3 && prev.textContent.length > 0) {
                                    var chars = Array.from(prev.textContent);
                                    var lastChar = chars.pop();
                                    baseText = lastChar + baseText;
                                    prev.textContent = chars.join('');
                                }
                                
                                // 뒤쪽 일반 텍스트 1자 흡수
                                if (next && next.nodeType === 3 && next.textContent.length > 0) {
                                    var chars = Array.from(next.textContent);
                                    var firstChar = chars.shift();
                                    baseText = baseText + firstChar;
                                    next.textContent = chars.join('');
                                }

                                // 3단계: 장평 축소 없이 루비 재구성 및 중앙 정렬 적용
                                ruby.innerHTML = ''; 
                                ruby.appendChild(document.createTextNode(baseText));
                                var rt = document.createElement('rt');
                                rt.textContent = rubyText;
                                ruby.appendChild(rt);
                                ruby.style.rubyAlign = 'center';
                            }
                        }
                    }
                