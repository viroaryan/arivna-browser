//! `!#if` / `!#else` / `!#endif` directives used by uBO and AdGuard lists.
//! Arivna presents itself as uBlock Origin on mobile Chromium.

fn token_value(tok: &str) -> bool {
    matches!(tok, "env_chromium" | "env_mobile" | "ext_ublock" | "cap_user_stylesheet" | "true")
}

struct Expr<'a> {
    toks: Vec<&'a str>,
    pos: usize,
}

impl<'a> Expr<'a> {
    fn new(s: &'a str) -> Self {
        let mut toks = Vec::new();
        let b = s.as_bytes();
        let mut i = 0;
        while i < b.len() {
            match b[i] {
                b' ' | b'\t' => i += 1,
                b'(' | b')' | b'!' => {
                    toks.push(&s[i..i + 1]);
                    i += 1;
                }
                b'&' | b'|' if i + 1 < b.len() && b[i + 1] == b[i] => {
                    toks.push(&s[i..i + 2]);
                    i += 2;
                }
                _ => {
                    let st = i;
                    while i < b.len() && !matches!(b[i], b' ' | b'\t' | b'(' | b')' | b'!' | b'&' | b'|') {
                        i += 1;
                    }
                    if i == st {
                        i += 1;
                    } else {
                        toks.push(&s[st..i]);
                    }
                }
            }
        }
        Expr { toks, pos: 0 }
    }
    fn peek(&self) -> Option<&'a str> {
        self.toks.get(self.pos).copied()
    }
    fn or(&mut self) -> bool {
        let mut v = self.and();
        while self.peek() == Some("||") {
            self.pos += 1;
            let r = self.and();
            v = v || r;
        }
        v
    }
    fn and(&mut self) -> bool {
        let mut v = self.unary();
        while self.peek() == Some("&&") {
            self.pos += 1;
            let r = self.unary();
            v = v && r;
        }
        v
    }
    fn unary(&mut self) -> bool {
        match self.peek() {
            Some("!") => {
                self.pos += 1;
                !self.unary()
            }
            Some("(") => {
                self.pos += 1;
                let v = self.or();
                if self.peek() == Some(")") {
                    self.pos += 1;
                }
                v
            }
            Some(t) => {
                self.pos += 1;
                token_value(t)
            }
            None => false,
        }
    }
}

pub fn eval(expr: &str) -> bool {
    Expr::new(expr).or()
}

/// Returns the lines that are active after evaluating conditional blocks.
pub fn active_lines(text: &str) -> Vec<&str> {
    let mut out = Vec::with_capacity(text.len() / 40);
    // Each frame: (this branch active, parent active)
    let mut stack: Vec<(bool, bool)> = Vec::new();
    let mut active = true;
    for line in text.lines() {
        let l = line.trim();
        if let Some(e) = l.strip_prefix("!#if ") {
            let v = eval(e);
            stack.push((v, active));
            active = active && v;
            continue;
        }
        if l == "!#else" {
            if let Some(top) = stack.last_mut() {
                top.0 = !top.0;
                active = top.1 && top.0;
            }
            continue;
        }
        if l == "!#endif" {
            if let Some((_, parent)) = stack.pop() {
                active = parent;
            }
            continue;
        }
        if active && !l.is_empty() && !l.starts_with('!') {
            out.push(l);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    #[test]
    fn exprs() {
        assert!(super::eval("env_mobile"));
        assert!(!super::eval("env_firefox"));
        assert!(super::eval("!env_firefox && (env_chromium || env_safari)"));
        let t = "a\n!#if env_firefox\nb\n!#else\nc\n!#endif\nd";
        assert_eq!(super::active_lines(t), vec!["a", "c", "d"]);
    }
}
