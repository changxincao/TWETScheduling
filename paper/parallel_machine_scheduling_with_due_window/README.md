# Manuscript source

`main.tex` is the current manuscript entry point. It contains the rewritten
abstract, introduction, literature review, problem description, formulations, and
solution method through the files in `sections/`.

`twet_outsourcing_models_revised_v42.tex` and `history/` are retained as legacy
working material. They are not included by `main.tex`.

Build the current manuscript from this directory:

```text
xelatex main.tex
bibtex main
xelatex main.tex
xelatex main.tex
```

The computational-study and conclusion sections remain explicit placeholders until
the final instance-generation protocol and comparison tables are fixed.
