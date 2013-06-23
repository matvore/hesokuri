;;; hesokuri -*- lexical-binding: t; coding: utf-8 -*-
;;; Personal distributed backup system

(defvar hesokuri-sources '()
  "Defines the locations from which to read hesokuri repos. It is a list of zero
or more REPO items, where a REPO item is in this form:
 (NAME
  (\"/foo/bar/path1/\"
   MAC-ADDRESS-USING-PATH-1-A
   MAC-ADDRESS-USING-PATH-1-B
   ...)
  (\"/home/jdoe/path2/\"
   MAC-ADDRESS-USING-PATH-2-A
   MAC-ADDRESS-USING-PATH-2-B
   ...)
  ...)
NAME is a lisp symbol identifying the repo in status and error messages. After
name follows one or more path settings, each path settings a list which contains
a path followed by one or more MAC addresses. Each MAC address has a copy of the
repo at the given path. Here is an example value for this variable for a
hypothetical user with two Mac OS machines and a Linux machine:

\((filing-cabinet
  (\"/Users/jdoe/filing-cabinet\"
   3c:07:55:62:31:3f
   12:f3:90:d5:1b:fd))
 (emacs-d
  (\"/Users/jdoe/.emacs.d\"
   3c:07:55:62:31:3f
   12:f3:90:d5:1b:fd)
  (\"/home/jdoe/.emacs.d\"
   4c:e7:7b:30:23:3d))
 (hesokuri
  (\"/Users/matvore/hesokuri\"
   3c:07:55:62:31:3f
   12:f3:90:d5:1b:fd)
  (\"/home/jdoe/hesokuri\"
   4c:e7:7b:30:23:3d)))

Notice that the Mac OS machines use the same directory structure, so the
directories only have to appear once in the list.
")

(defun hesokuri-mac-of (ip)
  "Given an IP address as a string, attempts to find the MAC address associated
with it. The MAC address is returned as a symbol in the form ff:ff:ff:ff:ff:ff.
Returns NIL if there was an error."
  (with-temp-buffer
    (let ((arp-result (call-process "arp" nil t nil ip)))
      (unless (eql 0 arp-result)
        (error "Error running arp for ip address %s (exitcode %s): %s"
               ip arp-result (buffer-string)))
      (let* ((arp-output (buffer-string))
             (mac-str-index
              (string-match "..?:..?:..?:..?:..?:..? " arp-output)))
        (unless mac-str-index
          (error "Could not find MAC addres in arp output: %s" arp-output))
        (intern (substring arp-output mac-str-index (- (match-end 0) 1)))))))
